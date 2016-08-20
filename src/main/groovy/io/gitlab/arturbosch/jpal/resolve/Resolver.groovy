package io.gitlab.arturbosch.jpal.resolve

import com.github.javaparser.ast.type.PrimitiveType
import com.github.javaparser.ast.type.Type
import groovy.transform.CompileStatic
import io.gitlab.arturbosch.jpal.ast.TypeHelper
import io.gitlab.arturbosch.jpal.core.CompilationStorage
import io.gitlab.arturbosch.jpal.internal.JdkHelper
import io.gitlab.arturbosch.jpal.internal.Validate

/**
 * Provides a static method to resolve the full qualified name of a class type.
 * Information about the imports, the package and assumptions of jdk classes are used
 * to predict the qualified type. Be aware that this approach does not work 100% as
 * star imports are ignored - they are generally considered as a code smell.
 *
 * {@code
 *
 * Usage:
 *
 * 	ClassOrInterfaceType myType = ...
 * 	CompilationUnit unit = ...
 * 	ResolutionData data = ResolutionData.of(unit)
 * 	QualifiedType qualifiedType = Resolver.getQualifiedTypeFromPackage(data, myType)
 *
 *}
 *
 * @author artur
 */
@CompileStatic
final class Resolver {

	private Resolver() {}

	/**
	 * Tries to find the correct qualified name. Considered options are
	 * primitives, boxed primitives, jdk types and reference types within imports or the package.
	 * This approach works on class or interface types as this type is searched
	 * from within the given type.
	 *
	 * @param data resolution data which must be provided from a compilation unit
	 * @param type type of given declaration
	 * @return the qualified type of given type
	 */
	static QualifiedType getQualifiedType(ResolutionData data, Type type) {
		Validate.notNull(data)
		Validate.notNull(type)

		if (type instanceof PrimitiveType) {
			return new QualifiedType(type.type.toString(), QualifiedType.TypeToken.PRIMITIVE)
		}

		def maybeClassOrInterfaceType = TypeHelper.getClassOrInterfaceType(type)

		if (maybeClassOrInterfaceType.isPresent()) {
			def realType = maybeClassOrInterfaceType.get()
			if (realType.isBoxedType()) {
				return new QualifiedType("java.lang." + realType.name, QualifiedType.TypeToken.BOXED_PRIMITIVE)
			} else {
				String name = realType.name
				def maybeFromImports = getFromImports(name, data)
				if (maybeFromImports.isPresent()) {
					return maybeFromImports.get()
				} else {
					if (JdkHelper.isPartOfJava(name)) {
						return new QualifiedType("java.lang." + name, QualifiedType.TypeToken.JAVA_REFERENCE)
					}
					// lets assume it is in the same package
					return new QualifiedType("$data.packageName.$name", QualifiedType.TypeToken.REFERENCE)
				}
			}
		}

		return new QualifiedType("UNKNOWN", QualifiedType.TypeToken.UNKNOWN)
	}

	private static Optional<QualifiedType> getFromImports(String name, ResolutionData data) {
		Validate.notEmpty(name)
		def importName = trimInnerClasses(name)

		def imports = data.imports
		if (imports.keySet().contains(importName)) {
			def qualifiedName = imports.get(importName)
			def qualifiedNameWithInnerClass = qualifiedName.substring(0, qualifiedName.lastIndexOf('.') + 1) + name
			return Optional.of(new QualifiedType(qualifiedNameWithInnerClass, QualifiedType.TypeToken.REFERENCE))
		} else if (CompilationStorage.isInitialized()) {
			return data.importsWithAsterisk.stream()
					.map { new QualifiedType("$it.$name", QualifiedType.TypeToken.REFERENCE) }
					.filter { CompilationStorage.getCompilationInfo(it).isPresent() }
					.findFirst()

		}
		return Optional.empty()
	}

	private static String trimInnerClasses(String name) {
		name.contains(".") ? name.substring(0, name.indexOf('.')) : name
	}
}
