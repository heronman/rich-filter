package net.agl.rest.filter

import net.agl.rest.filter.FilterParser.Companion.parse
import java.io.File

class FilterParserPresetTests {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            @Suppress("JAVA_CLASS_ON_COMPANION")
            javaClass.getResource("/test-cases/valid")!!.toURI()
                .let { File(it).listFiles { it.isFile && it.name.endsWith(".json") } }
                .forEach { file ->
                    try {
                        println(file.name)
                        val result = parse(file.readText())
                        println("${result}\n---")
                    } catch (e: Exception) {
                        println("${file.name}: ${e.message}")
                        e.printStackTrace(System.out)
                    }
                }
        }
    }
}
