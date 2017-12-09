package io.gitlab.arturbosch.jpal.core

import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import groovy.util.logging.Log
import io.gitlab.arturbosch.jpal.internal.PrefixedThreadFactory
import io.gitlab.arturbosch.jpal.internal.SmartCache
import io.gitlab.arturbosch.jpal.internal.StreamCloser
import io.gitlab.arturbosch.jpal.internal.Validate
import io.gitlab.arturbosch.jpal.resolution.QualifiedType
import io.gitlab.arturbosch.jpal.resolution.solvers.TypeSolver

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.regex.Pattern
import java.util.stream.Stream

/**
 * Adds cross referencing ability to javaparser by storing all compilation units
 * within a compilation info, which extends usability by providing the qualified type
 * and path etc to the compilation unit.
 *
 * This class is intended to be initialized before using javaparser. For this, we have
 * to provide a project base path and call {@code DefaultCompilationStorage.new ( projectPath )}.
 * The new method returns a reference to the singleton compilation storage.
 *
 * Internally a {@code ForkJoinPool} is used to compile all child paths and store their
 * compilation units in parallel.
 *
 * This is the only way to obtain a reference to the compilation storage, but mostly not
 * necessary as the compilation storage singleton is stored internal and provide a static only api.
 *
 * To obtain compilation info's, use the convenient methods:
 *
 * {@code
 * def maybeInfo = DefaultCompilationStorage.getCompilationInfo(path)
 * def maybeInfo = DefaultCompilationStorage.getCompilationInfo(qualifiedType)
 *}
 *
 * Don't use the compilation storage without initializing it.
 * If unsure call {@code DefaultCompilationStorage.isInitialized ( )}
 *
 * @author artur
 */
@Log
@CompileStatic
class DefaultCompilationStorage implements CompilationStorage {

	protected final SmartCache<QualifiedType, CompilationInfo> typeCache = new SmartCache<>()
	protected final SmartCache<Path, CompilationInfo> pathCache = new SmartCache<>()

	/*
	 * Attention: package tracking is not thread safe and is only done outside of concurrent
	 * compilation or type usage calculation. Do not concurrently update packages and do analysis
	 * with these set/map.
	 */
	protected final NavigableSet<String> packageNames = new ConcurrentSkipListSet<>()
	protected final ConcurrentMap<String, AtomicInteger> packageUsage = new ConcurrentHashMap<>()

	protected final JavaCompilationParser parser
	protected final CompilationInfoProcessor processor
	protected final TypeSolver typeSolver = new TypeSolver(this)
	protected final List<Pattern> pathFilters

	protected final ExecutorService executor

	@PackageScope
	DefaultCompilationStorage(CompilationInfoProcessor processor = null,
							  List<Pattern> pathFilters = new ArrayList<>(),
							  JavaCompilationParser javaParser = null,
							  ExecutorService executor = null) {
		this.executor = executor ?: Executors.newFixedThreadPool(
				Runtime.runtime.availableProcessors(),
				new PrefixedThreadFactory("jpal")).with { ExecutorService service ->
			Runtime.runtime.addShutdownHook { service.shutdown() }
			service
		}
		this.processor = processor
		this.pathFilters = pathFilters
		this.parser = javaParser ?: new JavaCompilationParser()
	}

	@PackageScope
	CompilationStorage initialize(Path root) {
		// first build compilation info's foundation
		Stream<Path> walker = getJavaFilteredFileStream(root)
		List<CompletableFuture<Void>> futures = walker.collect { Path path ->
			CompletableFuture
					.runAsync({ createCompilationInfo(path) }, executor)
					.exceptionally { log.log(Level.WARNING, "Error compiling $path:", it) }
		}
		CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).join()

		// search for used types after compilation as star imports are else not resolvable
		futures = allCompilationInfo.collect { info ->
			CompletableFuture
					.runAsync({ info.findUsedTypes(typeSolver) }, executor)
					.thenRun { if (processor) info.runProcessor(processor) }
					.exceptionally { log.log(Level.WARNING, "Error finding used types for $info.path:", it) }
		}
		CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).join()

		StreamCloser.quietly(walker)
		return this
	}

	private static Stream<Path> getJavaFilteredFileStream(Path root) {
		return Files.walk(root).parallel()
				.filter { it.toString().endsWith(".java") }
				.filter { !it.toString().endsWith("module-info.java") }
				.filter { !it.toString().endsWith("package-info.java") } as Stream<Path>
	}

	@Override
	Set<QualifiedType> getAllQualifiedTypes() {
		return Collections.unmodifiableSet(typeCache.keys())
	}

	@Override
	Collection<CompilationInfo> getAllCompilationInfo() {
		return Collections.unmodifiableCollection(new HashSet<CompilationInfo>(typeCache.values()))
	}

	@Override
	Optional<CompilationInfo> getCompilationInfo(Path path) {
		Validate.notNull(path)
		return pathCache.get(path)
	}

	@Override
	Optional<CompilationInfo> getCompilationInfo(QualifiedType qualifiedType) {
		Validate.notNull(qualifiedType)
		def qualifiedOuterType = qualifiedType.asOuterClass()
		return typeCache.get(qualifiedOuterType)
	}

	@Override
	Set<String> getStoredPackageNames() {
		return Collections.unmodifiableSet(packageNames)
	}

	/**
	 * Compiles from path or source code and return the compilation info.
	 * May be null!
	 */
	protected CompilationInfo createCompilationInfo(Path path, String code = null) {
		if (isPackageOrModuleInfo(path)) return null
		if (isFiltered(path)) return null
		def compile = code ? parser.compileFromCode(path, code) : parser.compile(path)
		compile.ifPresent { CompilationInfo info ->
			typeCache.put(info.qualifiedType, info)
			info.innerClasses.keySet().each {
				typeCache.put(it, info)
			}
			pathCache.put(path, info)
			addPackageName(info.qualifiedType.onlyPackageName)
		}
		return compile.orElse(null)
	}

	private boolean isFiltered(path) {
		!path.toString().endsWith(".java") || pathFilters.any { it.matcher(path.toString()).find() }
	}

	private static boolean isPackageOrModuleInfo(Path path) {
		def filename = path.fileName.toString()
		return filename == "package-info.java" || filename == "module-info.java"
	}

	/**
	 * Postprocessing of compilation info's. Mainly due to the reason that type resolver
	 * needs a full storage for finding used types. Parameter and result may be null!
	 */
	protected CompilationInfo findTypesAndRunProcessor(CompilationInfo info) {
		if (info) {
			info.findUsedTypes(typeSolver)
			if (processor) info.runProcessor(processor)
		}
		return info
	}

	protected void addPackageName(String maybeNewPackageName) {
		packageNames.add(maybeNewPackageName)
		packageUsage.putIfAbsent(maybeNewPackageName, new AtomicInteger())
		packageUsage.get(maybeNewPackageName).incrementAndGet()
	}

	protected void removePackageName(String packageName) {
		def counter = packageUsage.get(packageName)
		if (counter) {
			if (counter.get() <= 1) {
				packageUsage.remove(packageName)
				packageNames.remove(packageName)
			} else {
				counter.decrementAndGet()
			}
		} else {
			packageNames.remove(packageName)
		}
	}
}
