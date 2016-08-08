package io.gitlab.arturbosch.jpal.nested

import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.type.Type
import groovy.transform.CompileStatic
import io.gitlab.arturbosch.jpal.ast.NodeHelper
import io.gitlab.arturbosch.jpal.internal.Validate

/**
 * Stores inner class information of a compilation unit.
 *
 * @author artur
 */
@CompileStatic
class InnerClassesHandler {

	private String outerClassName
	private Set<String> innerClassesNames

	InnerClassesHandler(CompilationUnit unit) {
		Validate.notNull(unit)
		def types = unit.getTypes()
		if (types.size() >= 1) {
			def mainClass = types[0]
			innerClassesNames = NodeHelper.findNamesOfInnerClasses(mainClass)
			outerClassName = mainClass.name
		} else {
			throw new NoClassesException()
		}
	}

	private boolean isInnerClass(String className) {
		Validate.notNull(className)
		return innerClassesNames.contains(className)
	}

	String getUnqualifiedNameForInnerClass(Type type) {
		Validate.notNull(type)
		return isInnerClass(type.toStringWithoutComments()) ? "${outerClassName}.$type" : "$type"
	}

}