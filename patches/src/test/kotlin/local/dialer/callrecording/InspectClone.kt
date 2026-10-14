package local.dialer.callrecording

import java.io.File

fun main(args: Array<String>) {
    val methods = readMethods(File(args[0]))
    val output = File(args[1]).apply { mkdirs() }
    val names = setOf("Laabo;", "Laaex;", "Laabm;", "Laabg;", "Laaey;", "Laabl;")
    methods.values.filter { it.definingClass in names }.groupBy { it.definingClass }.forEach { (type, members) ->
        output.resolve("${type.drop(1).dropLast(1)}-dex.txt").writeText(members.joinToString("\n") { it.instructionDump() })
    }
    println("Read ${methods.size} methods from the actual Morphe clone")
}
