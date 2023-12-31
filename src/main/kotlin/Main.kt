@file:Suppress("UNCHECKED_CAST")

import com.google.gson.JsonParser
import com.google.gson.stream.JsonWriter
import org.objectweb.asm.*
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.signature.SignatureVisitor
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.MethodNode
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.*
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import kotlin.jvm.optionals.getOrNull
import kotlin.system.exitProcess

const val API = Opcodes.ASM9

const val LONG = "Ljava/lang/Long;"
const val INTEGER = "Ljava/lang/Integer;"
const val DOUBLE = "Ljava/lang/Double;"
const val FLOAT = "Ljava/lang/Float;"
const val BYTE = "Ljava/lang/Byte;"
const val BOOLEAN = "Ljava/lang/Boolean;"
const val SHORT = "Ljava/lang/Short;"
const val CHAR = "Ljava/lang/Char;"

const val MIXIN_ANNOTATION = "Lorg/spongepowered/asm/mixin/Mixin;"
const val SHADOW_ANNOTATION = "Lorg/spongepowered/asm/mixin/Shadow;"
const val ACCESSOR_ANNOTATION = "Lorg/spongepowered/asm/mixin/gen/Accessor;"
const val INVOKER_ANNOTATION = "Lorg/spongepowered/asm/mixin/gen/Invoker;"
const val INJECT_ANNOTATION = "Lorg/spongepowered/asm/mixin/injection/Inject;"
const val REDIRECT_ANNOTATION = "Lorg/spongepowered/asm/mixin/injection/Redirect;"
const val MODIFY_ARGS_ANNOTATION = "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;"

const val CALLBACK_INFO = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;"
const val CALLBACK_INFO_RETURNABLE = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;"

fun main(
    args : Array<String>
) {
    val timestamp = System.currentTimeMillis()

    fun exit(
        reason : String
    ) {
        println("$reason\nUsages: \"input mod file\" \"output mod file\" \"tiny mappings file\" \"remapped minecraft jar\"")
        exitProcess(0)
    }

    if(args.size != 4) {
        exit("Not enough arguments!")
    }

    val inputFile = File(args[0])
    val outputFile = File(args[1])
    val mappingsFile = File(args[2])
    val minecraftJar = JarFile(args[3])

    if(!inputFile.exists()) {
        exit("Input mod file does not exist!")
    }

    if(!mappingsFile.exists()) {
        exit("Could not load mappings!")
    }

    if(outputFile.exists()) {
        println("Output mod will be overwritten")
        println()

        outputFile.delete()
    }

    outputFile.createNewFile()

    val jos = JarOutputStream(FileOutputStream(outputFile))

    fun sumFlags(
        flags : Array<Int>
    ) : Int {
        var result = 0

        for (flag in flags) {
            result = result or flag
        }

        return result
    }

    fun read(
        bytes : ByteArray,
        visitor : ClassVisitor? = null,
        vararg flags : Int
    ) = ClassNode(API).also {
        ClassReader(bytes).accept(visitor ?: it, sumFlags(flags.toTypedArray()))
    }

    fun remap(
        bytes : ByteArray,
        remapper : Remapper,
        vararg flags : Int
    ) = ClassNode(API).also {
        ClassReader(bytes).accept(ClassRemapper(it, remapper), sumFlags(flags.toTypedArray()))
    }

    fun write(
        node : ClassNode,
        vararg flags : Int
    ) = ClassWriter(sumFlags(flags.toTypedArray())).also { node.accept(it) }.toByteArray()

    fun getAnnotation(
        name : String,
        nodes : List<AnnotationNode>?
    ) : AnnotationNode? {
        for(node in nodes ?: emptyList()) {
            if(node.desc == name) {
                return node
            }
        }

        return null
    }

    fun ClassNode.getAnnotation(
        name : String
    ) = getAnnotation(name, visibleAnnotations) ?: getAnnotation(name, invisibleAnnotations)

    fun FieldNode.getAnnotation(
        name : String
    ) = getAnnotation(name, visibleAnnotations) ?: getAnnotation(name, invisibleAnnotations)

    fun MethodNode.getAnnotation(
        name : String
    ) = getAnnotation(name, visibleAnnotations) ?: getAnnotation(name, invisibleAnnotations)

    fun FieldNode.hasAnnotations() = visibleAnnotations?.isNotEmpty() == true

    fun MethodNode.hasAnnotations() = visibleAnnotations?.isNotEmpty() == true

    fun AnnotationNode?.getValue(
        name : String
    ) = if(this?.values?.contains(name) == true) {
        values[values.indexOf(name) + 1]
    } else {
        null
    }

    fun MethodNode.createDescriptor(
        type : InjectionTypes
    ) : String {
        if(!type.generatedDescriptor) {
            return "()V"
        }

        val void = desc.contains(CALLBACK_INFO)
        val split1 = desc.split(")")
        val params = split1[0].removePrefix("(").removeSuffix(if(void) CALLBACK_INFO else CALLBACK_INFO_RETURNABLE)
        var returnType = if(!void && type == InjectionTypes.INJECT) {
            val signatureNode = SignatureNode(signature)

            signatureNode.params[signatureNode.params.indexOf(CALLBACK_INFO_RETURNABLE) + 1]
        } else {
            split1[1]
        }

        returnType = when(returnType) {
            BOOLEAN -> "Z"
            CHAR -> "C"
            BYTE -> "B"
            SHORT -> "S"
            INTEGER -> "I"
            FLOAT -> "F"
            LONG -> "J"
            DOUBLE -> "D"
            else -> returnType
        }

        return "($params)$returnType"
    }

    val inheritances = mutableMapOf<String, MutableList<String>>()

    val remapEntries = mutableSetOf<RemapEntry>()

    val mixinEntries = mutableMapOf<MixinAnnotationEntry, MutableList<IAnnotationEntry>>()
    val tinyEntries = mutableListOf<TinyEntry>()
    val refmapEntries = mutableMapOf<String, MutableList<IEntry>>()

    fun buildHierarchy(
        current : String,
        cache : Map<String, MutableList<String>>,
        hierarchy : MutableCollection<String> = mutableListOf()
    ) : Collection<String> {
        hierarchy.add(current)

        return if(cache.contains(current)) {
            for(cached in cache[current]!!) {
                buildHierarchy(cached, cache, hierarchy)
            }

            hierarchy
        } else {
            hierarchy
        }
    }

    fun findTinyEntry(
        name : String,
        clazz : String,
        type : TinyEntryTypes,
        getter : (TinyEntry) -> String
    ) : TinyEntry? {
        for(tinyEntry in tinyEntries) {
            if(getter(tinyEntry) == name && (tinyEntry.clazz == clazz || "L${tinyEntry.clazz};" == clazz || tinyEntry.clazz == "L$clazz;") && tinyEntry.type == type) {
                return tinyEntry
            }
        }

        return null
    }

    fun mapDescriptor(
        descriptor : String
    ) = if(descriptor.startsWith('L') || (descriptor.startsWith('[') && descriptor.contains("[L"))) {
        val arrayDimension = if(descriptor.contains("[")) {
            descriptor.substring(0..descriptor.lastIndexOf('['))
        } else {
            ""
        }

        val className = descriptor.removePrefix(arrayDimension)
        val classEntry = findTinyEntry(className, "", TinyEntryTypes.CLASS) { it.named }

        "${arrayDimension}${classEntry?.intermediary ?: className}"
    } else {
        descriptor
    }

    fun mapMethodType(
        signature : String
    ) = try {
        val signatureNode = SignatureNode(signature)
        val params = mutableListOf<String>()
        val returnType = mapDescriptor(signatureNode.returnType)

        for(param in signatureNode.params) {
            params.add(mapDescriptor(param))
        }

        "(${params.joinToString("")})$returnType"
    } catch(throwable : Throwable) {
        exit("Found incorrect signature $signature")

        throw throwable
    }

    fun findFieldTinyEntry(
        name : String,
        classes : Collection<String>
    ) : TinyEntry? {
        for(clazz in classes) {
            val entry = findTinyEntry(name, clazz, TinyEntryTypes.FIELD) { it.named }

            if(entry != null) {
                return entry
            }
        }

        return null
    }

    fun findFieldTinyEntry(
        name : String,
        clazz : String
    ) = findFieldTinyEntry(name, listOf(clazz)) ?: findFieldTinyEntry(name, buildHierarchy(clazz, inheritances))

    fun findMethodTinyEntry(
        name : String,
        classes : Collection<String>
    ) = if(name.contains("(")) {
        val methodName = name.split("(")[0]
        val descriptor = mapMethodType("(${name.split("(")[1]}")

        val mappedName = "$methodName$descriptor"

        run {
            for(clazz in classes) {
                val entry = findTinyEntry(mappedName, clazz, TinyEntryTypes.METHOD) { "${it.named}${it.descriptor}" }

                if(entry != null) {
                    return@run entry
                }
            }

            return@run null
        }
    } else {
        run {
            for(clazz in classes) {
                val entry = findTinyEntry(name, clazz, TinyEntryTypes.METHOD) { it.named }

                if(entry != null) {
                    return@run entry
                }
            }

            return@run null
        }
    }

    fun findMethodTinyEntry(
        name : String,
        clazz : String
    ) = findMethodTinyEntry(name, listOf(clazz)) ?: findMethodTinyEntry(name, buildHierarchy(clazz, inheritances))

    val jarFile = JarFile(args[0])

    var accessors = 0
    var invokers = 0
    var injects = 0
    var redirects = 0
    var modifyArgs = 0

    val fabricModJson = jarFile.entries().toList().stream().filter { it.name == "fabric.mod.json" }.findFirst().getOrNull()

    val modloader = if(fabricModJson != null) {
        ModLoaders.Fabric
    } else {
        ModLoaders.Forge
    }

    println("Found $modloader environment")

    val mixinConfig = modloader.mixinConfigGetter(jarFile)

    if(mixinConfig == null) {
        exit("Could not find mixin config!")
    } else {
        println("Found \"${mixinConfig.name}\" mixin config")
    }

    val mixinsPackage : String
    val refmap : String

    run {
        val bytes = jarFile.getInputStream(mixinConfig!!).readBytes()
        val json = JsonParser.parseString(String(bytes))
        val jobject = json.asJsonObject

        mixinsPackage = (jobject["package"]?.asString ?: "").replace(".", "/")
        refmap = jobject["refmap"]?.asString ?: ""
    }

    println("Found \"$mixinsPackage\" mixins package")
    println("Found \"$refmap\" refmap")

    val accesswidener = if(modloader == ModLoaders.Fabric) jarFile.entries().toList().stream().filter { it.name.endsWith(".accesswidener") }.findFirst().getOrNull().also {
        if(it != null) {
            println("Found ${it.name} accesswidener")
        }
    } else null

    println()
    println("Processing mixins")

    for(entry in jarFile.entries()) {
        val bytes = jarFile.getInputStream(entry).readBytes()
        var delayedWrite = false

        if(entry.name == refmap) {
            continue
        } else if(entry.name.startsWith(mixinsPackage) && entry.name.endsWith(".class")) {
            val classNode = read(bytes)
            val mixinAnnotation = classNode.getAnnotation(MIXIN_ANNOTATION)

            if(mixinAnnotation != null) {
                val mixinEntry = MixinAnnotationEntry(
                    classNode.name,
                    mixinAnnotation.getValue("value") as Collection<Type>?,
                    mixinAnnotation.getValue("targets") as Collection<String>?
                )

                mixinEntries[mixinEntry] = mutableListOf()

                for(fieldNode in classNode.fields) {
                    val shadowAnnotation = fieldNode.getAnnotation(SHADOW_ANNOTATION)

                    if(shadowAnnotation != null || !fieldNode.hasAnnotations()) {
                        delayedWrite = true
                    }
                }

                for(methodNode in classNode.methods) {
                    val shadowAnnotation = methodNode.getAnnotation(SHADOW_ANNOTATION)
                    val accessorAnnotation = methodNode.getAnnotation(ACCESSOR_ANNOTATION)
                    val invokerAnnotation = methodNode.getAnnotation(INVOKER_ANNOTATION)
                    val injectAnnotation = methodNode.getAnnotation(INJECT_ANNOTATION)
                    val redirectAnnotation = methodNode.getAnnotation(REDIRECT_ANNOTATION)
                    val modifyArgsAnnotation = methodNode.getAnnotation(MODIFY_ARGS_ANNOTATION)

                    if(shadowAnnotation != null || !methodNode.hasAnnotations()) {
                        delayedWrite = true
                    }

                    fun processGenAnnotation(
                        annotationNode : AnnotationNode?,
                        type : GenTypes,
                        increment : () -> Unit
                    ) {
                        if(annotationNode != null) {
                            val value = annotationNode.getValue("value") as String?
                            val remap = annotationNode.getValue("remap") as Boolean? ?: true

                            if(value != null && remap) {
                                mixinEntries[mixinEntry]!!.add(GenAnnotationEntry(value, type))
                                increment()
                            }
                        }
                    }

                    fun processInjectionAnnotation(
                        annotationNode : AnnotationNode?,
                        type : InjectionTypes,
                        increment : () -> Unit
                    ) {
                        if(annotationNode != null) {
                            val method = annotationNode.getValue("method") as Collection<String>
                            val at = if(type.singleAt) listOf(annotationNode.getValue("at") as AnnotationNode) else annotationNode.getValue("at") as Collection<AnnotationNode>
                            val remap = annotationNode.getValue("remap") as Boolean? ?: true

                            if(remap) {
                                val descriptor = methodNode.createDescriptor(type)
                                val ats = mutableListOf<At>()

                                for(at0 in at) {
                                    val value = at0.getValue("value") as String
                                    val target = at0.getValue("target") as String? ?: ""

                                    try {
                                        val injectType = InjectTypes.valueOf(value)

                                        ats.add(At(injectType, target))
                                    } catch(_ : Exception) {
                                        println("Warning! @At(value = \"$value\") not supported")
                                    }
                                }

                                mixinEntries[mixinEntry]!!.add(InjectionAnnotationEntry(method, ats, descriptor, type))
                                increment()
                            }
                        }
                    }

                    processGenAnnotation(accessorAnnotation, GenTypes.ACCESSOR) { accessors++ }
                    processGenAnnotation(invokerAnnotation, GenTypes.INVOKER) { invokers++ }
                    processInjectionAnnotation(injectAnnotation, InjectionTypes.INJECT) { injects++ }
                    processInjectionAnnotation(redirectAnnotation, InjectionTypes.REDIRECT) { redirects++ }
                    processInjectionAnnotation(modifyArgsAnnotation, InjectionTypes.MODIFY_ARGS) { modifyArgs++ }
                }

                if(delayedWrite) {
                    remapEntries.add(RemapEntry(entry, mixinEntry))
                }
            }
        }

        if(!delayedWrite) {
            jos.putNextEntry(entry)
            jos.write(bytes)
            jos.closeEntry()
        }
    }

    println("Processed $accessors @Accessor annotations")
    println("Processed $invokers @Invoker annotations")
    println("Processed $injects @Inject annotations")
    println("Processed $redirects @Redirect annotations")
    println("Processed $modifyArgs @ModifyArgs annotations")
    println()
    println("Processing mappings")

    for(line in mappingsFile.readLines()) {
        val split = line.split("\t")

        if(split[0].startsWith("v")) {
            if(split[0] != "v1") {
                exit("Only v1 mappings are supported!")
            }

            continue
        }

        when(split[0]) {
            "CLASS" -> {
                val intermediary = "L${split[1]};"
                val named = "L${split[2]};"

                val entry = TinyEntry(intermediary, named, "", "", TinyEntryTypes.CLASS)

                tinyEntries.add(entry)
            }

            "FIELD", "METHOD" -> {
                val intermediary = split[3]
                val named = split[4]
                var descriptor = split[2]
                val clazz = split[1]

                if(descriptor.endsWith(")")) {
                    descriptor += "V"
                }

                val entry = TinyEntry(intermediary, named, descriptor, clazz, TinyEntryTypes.valueOf(split[0]))

                tinyEntries.add(entry)
            }

            else -> println("Warning! ${split[0]} tiny entry not supported")
        }
    }

    println("Processed ${tinyEntries.size} mapping entries")
    println()
    println("Caching minecraft jar")

    for(entry in minecraftJar.entries()) {
        if(entry.name.startsWith("net/minecraft/") && entry.name.endsWith(".class")) {
            val `is` = minecraftJar.getInputStream(entry)!!
            val bytes = `is`.readBytes()
            val classNode = read(bytes)
            val classEntry = findTinyEntry("L${classNode.name};", "", TinyEntryTypes.CLASS) { it.named }
            val className = classEntry?.intermediary ?: classNode.name

            val inheritanceTypes = mutableListOf<String>()

            fun processClass(
                name : String
            ) {
                if(name.startsWith("net/minecraft/")) {
                    inheritanceTypes.add(findTinyEntry("L$name;", "", TinyEntryTypes.CLASS) { it.named }?.intermediary ?: name)
                }
            }

            processClass(classNode.superName)

            for(interfaze in classNode.interfaces) {
                processClass(interfaze)
            }

            if(inheritanceTypes.isNotEmpty()) {
                inheritances[className] = inheritanceTypes
            }
        }
    }

    println("Cached ${inheritances.size} inheritances")
    println()
    println("Generating refmap entries")

    for((mixinEntry, annotationEntries) in mixinEntries) {
        if(!refmapEntries.contains(mixinEntry.name)) {
            refmapEntries[mixinEntry.name] = mutableListOf()
        }

        if(!mixinEntry.classes[0].contains("net/minecraft/")) {
            val classEntry = findTinyEntry("L${mixinEntry.classes[0]};", "", TinyEntryTypes.CLASS) { it.named }

            if(classEntry != null) {
                refmapEntries[mixinEntry.name]!!.add(MixinEntry(classEntry.named, classEntry.intermediary))
            }
        }

        for(annotationEntry in annotationEntries) {
            if(annotationEntry is GenAnnotationEntry) {
                when(annotationEntry.type) {
                    GenTypes.ACCESSOR -> {
                        val fieldEntry = findTinyEntry(annotationEntry.name, mixinEntry.classes[0], TinyEntryTypes.FIELD) { it.named }

                        if(fieldEntry != null) {
                            refmapEntries[mixinEntry.name]!!.add(AccessorEntry(annotationEntry.name, fieldEntry.intermediary, fieldEntry.descriptor))
                        }
                    }

                    GenTypes.INVOKER -> {
                        val methodEntry = findMethodTinyEntry(annotationEntry.name, mixinEntry.classes[0])

                        if(methodEntry != null) {
                            refmapEntries[mixinEntry.name]!!.add(InvokerEntry(annotationEntry.name, methodEntry.intermediary, methodEntry.descriptor))
                        }
                    }
                }
            }

            if(annotationEntry is InjectionAnnotationEntry) {
                for(method in annotationEntry.methods) {
                    val methodEntry = findMethodTinyEntry("$method${if(annotationEntry.type.generatedDescriptor) annotationEntry.descriptor else ""}", mixinEntry.classes[0])

                    if(methodEntry != null) {
                        for(at in annotationEntry.ats) {
                            when(at.value) {
                                InjectTypes.INVOKE -> {
                                    val split1 = at.target.split(";")
                                    val split2 = at.target.split("(")

                                    val namedClass = "${split1[0]};"
                                    val namedMethod = split2[0].removePrefix(namedClass)
                                    val namedDescriptor = "(${split2[1]}"

                                    val classEntry = findTinyEntry(namedClass, "", TinyEntryTypes.CLASS) { it.named }

                                    if(classEntry != null) {
                                        val methodEntry2 = findMethodTinyEntry("$namedMethod$namedDescriptor", classEntry.intermediary)

                                        if(methodEntry2 != null) {
                                            val intermediaryClass = classEntry.intermediary
                                            val intermediaryMethod = methodEntry2.intermediary
                                            val intermediaryDescriptor = methodEntry2.descriptor

                                            refmapEntries[mixinEntry.name]!!.add(InvokeEntry(at.target, intermediaryClass, intermediaryMethod, intermediaryDescriptor))
                                        }
                                    }
                                }

                                InjectTypes.FIELD -> {
                                    val split1 = at.target.split(";")
                                    val split2 = at.target.split(":")

                                    val namedClass = "${split1[0]};"
                                    val namedField = split2[0].removePrefix(namedClass)

                                    val classEntry = findTinyEntry(namedClass, "", TinyEntryTypes.CLASS) { it.named }

                                    if(classEntry != null) {
                                        val fieldEntry = findTinyEntry(namedField, classEntry.intermediary, TinyEntryTypes.FIELD) { it.named }

                                        if(fieldEntry != null) {
                                            val intermediaryClass = classEntry.intermediary
                                            val intermediaryField = fieldEntry.intermediary
                                            val intermediaryDescriptor = fieldEntry.descriptor

                                            refmapEntries[mixinEntry.name]!!.add(FieldEntry(at.target, intermediaryClass, intermediaryField, intermediaryDescriptor))
                                        }
                                    }
                                }

                                else -> { }
                            }
                        }

                        refmapEntries[mixinEntry.name]!!.add(InjectEntry(method, methodEntry.intermediary, methodEntry.descriptor, mixinEntry.classes[0]))
                    }
                }
            }
        }
    }

    println()
    println("Remapping shadow/override fields/methods")

    var shadowFields = 0
    var overrideMethods = 0

    for(remapEntry in remapEntries) {
        val `is` = jarFile.getInputStream(remapEntry.jar)
        val bytes = `is`.readBytes()
        val classNode = remap(bytes, OverrideRemapper(
            remapEntry,
            { name, clazz -> findFieldTinyEntry(name, clazz) },
            { name, clazz -> findMethodTinyEntry(name, clazz) },
            { shadowFields++ },
            { overrideMethods++ },
            mixinsPackage
        ))

        jos.putNextEntry(remapEntry.jar)
        jos.write(write(classNode))
        jos.closeEntry()
    }

    println("Remapped $shadowFields shadow fields")
    println("Remapped $overrideMethods override methods")

    if(accesswidener != null) {
        println()
        println("Remapping accesswidener")

        var classes = 0
        var fields = 0
        var methods = 0

        val `is` = jarFile.getInputStream(accesswidener)
        val bytes = `is`.readBytes()

        val remappedLines = mutableListOf<String>()

        for(line in String(bytes).split("\n")) {
            val split = line.split(Regex("\\s"))

            when(split[0]) {
                "accessWidener" -> {
                    remappedLines.add("accessWidener\tv1\tintermediary")
                }

                "accessible", "mutable" -> {
                    when(split[1]) {
                        "class" -> {
                            val className = "L${split[2]};"
                            val classEntry = findTinyEntry(className, "", TinyEntryTypes.CLASS) { it.named }

                            remappedLines.add("${split[0]}\tclass\t${(classEntry?.intermediary ?: className).removePrefix("L").removeSuffix(";")}")

                            if(classEntry != null) {
                                classes++
                            }
                        }

                        "field", "method" -> {
                            val className = "L${split[2]};"
                            val classEntry = findTinyEntry(className, "", TinyEntryTypes.CLASS) { it.named }

                            if(classEntry != null) {
                                val name = split[3]
                                val descriptor = if(split[1] == "field") mapDescriptor(split[4]) else mapMethodType(split[4])
                                val entry = findTinyEntry("$name$descriptor", classEntry.intermediary, TinyEntryTypes.valueOf(split[1].uppercase(Locale.getDefault()))) { "${it.named}${it.descriptor}" }

                                if(entry != null) {
                                    remappedLines.add("${split[0]}\t${split[1]}\t${classEntry.intermediary.removePrefix("L").removeSuffix(";")}\t${entry.intermediary}\t${entry.descriptor}")

                                    if(split[1] == "field") {
                                        fields++
                                    } else {
                                        methods++
                                    }
                                }
                            } else {
                                remappedLines.add(line)
                            }
                        }
                    }
                }
            }
        }

        jos.putNextEntry(accesswidener)
        jos.write(remappedLines.joinToString("\n").toByteArray())
        jos.closeEntry()

        println("Remapped $classes class accesswidener entries")
        println("Remapped $fields field accesswidener entries")
        println("Remapped $methods methods accesswidener entries")
    }

    println()
    println("Writing refmap entries")

    var written = 0

    val refmapZipEntry = ZipEntry(refmap)

    jos.putNextEntry(refmapZipEntry)

    val writer = JsonWriter(OutputStreamWriter(jos))

    fun JsonWriter.entries() {
        for((mixinName, entries) in refmapEntries) {
            name(mixinName)
            beginObject()

            val keys = mutableListOf<String>()

            for(entry in entries) {
                if(!keys.contains(entry.key())) {
                    keys.add(entry.key())
                    name(entry.key())
                    value(entry.value())
                    written++
                }
            }

            endObject()
        }
    }

    writer.setIndent("\t")

    writer.beginObject()//root?
    writer.name("mappings")
    writer.beginObject()//mappings
    writer.entries()
    writer.endObject()//mappings
    writer.name("data")
    writer.beginObject()//data
    writer.name(modloader.dataJobjectName)
    writer.beginObject()//named:intermediary
    writer.entries()
    writer.endObject()//named:intermediary
    writer.endObject()//data
    writer.endObject()//root?

    writer.close()
    jos.close()

    println("Written $written entries")
    println()
    println("Everything took ${System.currentTimeMillis() - timestamp} ms!")
}

enum class GenTypes {
    ACCESSOR,
    INVOKER
}

enum class InjectionTypes(
    val singleAt : Boolean,
    val generatedDescriptor : Boolean
) {
    INJECT(false, true),
    REDIRECT(true, false),
    MODIFY_ARGS(true, false)
}

enum class TinyEntryTypes {
    CLASS,
    FIELD,
    METHOD
}

class TinyEntry(
    val intermediary : String,
    val named : String,
    val descriptor : String,
    val clazz : String,
    val type : TinyEntryTypes
)

class RemapEntry(
    val jar : JarEntry,
    val mixin : MixinAnnotationEntry
)

interface IAnnotationEntry

class MixinAnnotationEntry(
    val name : String,
    classes : Collection<Type>?,
    targets : Collection<String>?
) : IAnnotationEntry {
    val classes = mutableListOf<String>().also {
        for (clazz in classes ?: emptyList()) {
            it.add(clazz.toString())
        }

        it.addAll(targets ?: emptyList())
    }
}

class GenAnnotationEntry(
    val name : String,
    val type : GenTypes
) : IAnnotationEntry

class InjectionAnnotationEntry(
    val methods : Collection<String>,
    val ats : Collection<At>,
    val descriptor : String,
    val type : InjectionTypes
) : IAnnotationEntry

enum class InjectTypes {
    HEAD,
    TAIL,
    RETURN,
    INVOKE,
    FIELD,
    JUMP
}

class At(
    val value : InjectTypes,
    val target : String
)

interface IEntry {
    fun key() : String
    fun value() : String
}

abstract class GenEntry(
    private val named : String,
    protected val intermediary : String,
    protected val descriptor : String
) : IEntry {
    override fun key() = named
    override fun value() = "$intermediary$descriptor"
}

class AccessorEntry(
    named : String,
    intermediary : String,
    descriptor : String
) : GenEntry(
    named,
    intermediary,
    descriptor
) {
    override fun value() = "$intermediary:$descriptor"
}

class InvokerEntry(
    named : String,
    intermediary : String,
    descriptor : String
) : GenEntry(
    named,
    intermediary,
    descriptor
)

class InjectEntry(
    private val named : String,
    private val intermediary : String,
    private val descriptor : String,
    private val clazz : String
) : IEntry {
    override fun key() = named
    override fun value() = "$clazz$intermediary$descriptor"
}

class InvokeEntry(
    private val named : String,
    private val intermediaryClass : String,
    private val intermediaryName : String,
    private val intermediaryDescriptor : String
) : IEntry {
    override fun key() = named
    override fun value() = "$intermediaryClass$intermediaryName$intermediaryDescriptor"
}

class FieldEntry(
    private val named : String,
    private val intermediaryClass : String,
    private val intermediaryName : String,
    private val intermediaryDescriptor : String
) : IEntry {
    override fun key() = named
    override fun value() = "$intermediaryClass$intermediaryName:$intermediaryDescriptor"
}

class MixinEntry(
    private val named : String,
    private val intermediary : String
) : IEntry {
    override fun key() = named
    override fun value() = intermediary
}

class SignatureNode(
    signature : String
) : SignatureVisitor(API) {
    private var listeningParams = false
    private var listeningReturnType = false
    private var arrayDimension = 0

    val params = mutableListOf<String>()
    var returnType = "V"

    init {
        SignatureReader(signature).accept(this)
    }

    override fun visitParameterType() = this.also {
        listeningParams = true
        listeningReturnType = false
        arrayDimension = 0
    }

    override fun visitReturnType() = this.also {
        listeningParams = false
        listeningReturnType = true
        arrayDimension = 0
    }

    override fun visitArrayType() = this.also {
        arrayDimension++
    }

    override fun visitBaseType(
        descriptor : Char
    ) {
        if(listeningParams) {
            params.add("${"[".repeat(arrayDimension)}$descriptor")

            arrayDimension = 0
        } else if(listeningReturnType) {
            returnType = "${"[".repeat(arrayDimension)}$descriptor"

            listeningReturnType = false
            arrayDimension = 0
        }

        super.visitBaseType(descriptor)
    }

    override fun visitClassType(
        name : String
    ) {
        if(listeningParams) {
            params.add("${"[".repeat(arrayDimension)}L$name;")

            arrayDimension = 0
        } else if(listeningReturnType) {
            returnType = "${"[".repeat(arrayDimension)}L$name;"

            listeningReturnType = false
            arrayDimension = 0
        }

        super.visitClassType(name)
    }

    override fun visitEnd() {
        listeningParams = false
        listeningReturnType = false
        arrayDimension = 0

        super.visitEnd()
    }
}

class OverrideRemapper(
    private val entry : RemapEntry,
    private val fieldEntryFinder : (String, String) -> TinyEntry?,
    private val methodEntryFinder : (String, String) -> TinyEntry?,
    private val fieldIncreaser : () -> Unit,
    private val methodIncreaser : () -> Unit,
    private val mixinsPackage : String
) : Remapper() {
    override fun mapFieldName(
        owner : String,
        name : String,
        descriptor : String
    ) = if(owner.contains(mixinsPackage)) {
        println("$owner $name $descriptor")
        fieldEntryFinder(name, entry.mixin.classes[0])?.intermediary.also { if(it != null) fieldIncreaser() } ?: name
    } else {
        name
    }

    override fun mapMethodName(
        owner : String,
        name : String,
        descriptor : String
    ) = if(owner.contains(mixinsPackage)) {
        println("$owner $name $descriptor")
        methodEntryFinder("$name$descriptor", entry.mixin.classes[0])?.intermediary.also { if(it != null) methodIncreaser() } ?: name
    } else {
        name
    }
}

//TODO: support for a few mixin configs
 enum class ModLoaders(
     val mixinConfigGetter : (JarFile) -> JarEntry?,
     val dataJobjectName : String
 ) {
     Forge(
         {
             val mixinConfigs = it.manifest.mainAttributes.getValue("MixinConfigs")
             val mixinConfig = if(mixinConfigs.contains(",")) mixinConfigs.split(",")[0] else mixinConfigs

             it.entries().toList().stream().filter { entry -> entry.name == mixinConfig }.findFirst().getOrNull()
         },
         "searge"
     ),
     Fabric(
         {
             val fabricModJson = it.entries().toList().stream().filter { entry -> entry.name == "fabric.mod.json" }.findFirst().getOrNull()

             if(fabricModJson == null) {
                 null
             } else {
                  val `is` = it.getInputStream(fabricModJson)
                  val bytes = `is`.readBytes()
                  val json = JsonParser.parseString(String(bytes))
                  val jobject = json.asJsonObject

                 val mixins = jobject["mixins"].asJsonArray!!
                 val mixinConfig = mixins[0].asString
                 it.entries().toList().stream().filter { entry -> entry.name == mixinConfig }.findFirst().getOrNull()
             }
         },
         "named:intermediary"
     )
 }