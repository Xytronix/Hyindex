// Copyright 2026 Hyve. All rights reserved.
package com.hyve.knowledge.extraction

import com.github.javaparser.ParseProblemException
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.AccessSpecifier
import com.github.javaparser.ast.body.*
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.visitor.VoidVisitorAdapter
import java.io.File
import java.security.MessageDigest


object JavaChunker {


    init {
        StaticJavaParser.getParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
    }

    private val PACKAGE_RE = Regex("""(?m)^\s*package\s+([\w.]+)\s*;""")
    private val TYPE_DECL_RE = Regex("""\b(?:class|interface|enum|record)\s+([A-Za-z_]\w*)""")
    private const val FALLBACK_EMBED_CHARS = 4000


    private fun beginLine(node: com.github.javaparser.ast.Node): Int {
        val javadocLine = node.comment.filter { it is com.github.javaparser.ast.comments.JavadocComment }
            .flatMap { it.begin }.map { it.line }
        return javadocLine.orElseGet { node.begin.map { it.line }.orElse(0) }
    }


    fun chunkFile(file: File): List<MethodChunk> {
        if (file.name == "package-info.java") return emptyList()

        val source = file.readText()
        val fileHash = sha256(source)

        val cu: CompilationUnit = try {
            StaticJavaParser.parse(source)
        } catch (e: ParseProblemException) {
            return fallbackChunks(file, source, fileHash)
        }

        val packageName = cu.packageDeclaration.map { it.nameAsString }.orElse("")
        val imports = cu.imports.map { it.toString().trim() }

        val chunks = mutableListOf<MethodChunk>()

        cu.accept(object : VoidVisitorAdapter<Void>() {
            override fun visit(n: ClassOrInterfaceDeclaration, arg: Void?) {
                extractFromType(n, packageName, imports, file, fileHash, source, chunks)
                super.visit(n, arg)
            }

            override fun visit(n: EnumDeclaration, arg: Void?) {
                extractFromEnum(n, packageName, imports, file, fileHash, source, chunks)
                super.visit(n, arg)
            }

            override fun visit(n: RecordDeclaration, arg: Void?) {
                extractFromRecord(n, packageName, imports, file, fileHash, source, chunks)
                super.visit(n, arg)
            }
        }, null)


        if (chunks.isEmpty()) {
            return fallbackChunks(file, source, fileHash, parsed = true, astFqcn = primaryTypeFqcn(cu))
        }
        return disambiguateOverloads(chunks)
    }

    private fun disambiguateOverloads(chunks: List<MethodChunk>): List<MethodChunk> {
        val seen = HashMap<String, Int>()
        return chunks.map { chunk ->
            if (chunk.methodName.isBlank()) return@map chunk
            val occurrence = seen.merge(chunk.id, 1, Int::plus)!!
            if (occurrence == 1) chunk else chunk.copy(id = "${chunk.id}~$occurrence")
        }
    }


    fun chunkDirectory(
        dir: File,
        pathFilter: ((String) -> Boolean)? = null,
        onProgress: ((String, Int, Int) -> Unit)? = null,
    ): List<MethodChunk> {
        val javaFiles = dir.walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .filter { file ->
                if (pathFilter == null) true
                else pathFilter(file.relativeTo(dir).path.replace('\\', '/'))
            }
            .toList()

        val allChunks = mutableListOf<MethodChunk>()
        for ((idx, file) in javaFiles.withIndex()) {
            onProgress?.invoke(file.name, idx, javaFiles.size)
            try {
                allChunks.addAll(chunkFile(file))
            } catch (e: Exception) {
                System.err.println("Failed to chunk ${file.name}: ${e.message}")
            }
        }
        return allChunks
    }

    private fun extractFromType(
        typeDecl: ClassOrInterfaceDeclaration,
        packageName: String,
        imports: List<String>,
        file: File,
        fileHash: String,
        source: String,
        chunks: MutableList<MethodChunk>,
    ) {
        val className = typeDecl.fullyQualifiedName.orElse(
            if (packageName.isNotEmpty()) "$packageName.${typeDecl.nameAsString}"
            else typeDecl.nameAsString
        )


        val fields = typeDecl.fields.take(20).map { field ->
            field.toString().trim().let { s ->

                val eqIdx = s.indexOf('=')
                if (eqIdx > 0 && s.length - eqIdx > 50) s.substring(0, eqIdx + 50) + "..." else s
            }
        }


        for (method in typeDecl.methods) {
            val chunk = buildMethodChunk(
                className, packageName, method.nameAsString,
                method.declarationAsString, method.toString(),
                beginLine(method),
                method.end.map { it.line }.orElse(0),
                imports, fields, file, fileHash,
                facetsOf(method, method.body.orElse(null)),
            )
            chunks.add(chunk)
        }


        for (ctor in typeDecl.constructors) {
            val chunk = buildMethodChunk(
                className, packageName, "<init>",
                ctor.declarationAsString, ctor.toString(),
                beginLine(ctor),
                ctor.end.map { it.line }.orElse(0),
                imports, fields, file, fileHash,
                facetsOf(ctor, ctor.body),
            )
            chunks.add(chunk)
        }
    }

    private fun extractFromEnum(
        enumDecl: EnumDeclaration,
        packageName: String,
        imports: List<String>,
        file: File,
        fileHash: String,
        source: String,
        chunks: MutableList<MethodChunk>,
    ) {
        val className = if (packageName.isNotEmpty()) "$packageName.${enumDecl.nameAsString}"
        else enumDecl.nameAsString

        val fields = enumDecl.entries.take(20).map { it.nameAsString }

        for (method in enumDecl.methods) {
            val chunk = buildMethodChunk(
                className, packageName, method.nameAsString,
                method.declarationAsString, method.toString(),
                beginLine(method),
                method.end.map { it.line }.orElse(0),
                imports, fields, file, fileHash,
                facetsOf(method, method.body.orElse(null)),
            )
            chunks.add(chunk)
        }

        for (ctor in enumDecl.constructors) {
            val chunk = buildMethodChunk(
                className, packageName, "<init>",
                ctor.declarationAsString, ctor.toString(),
                beginLine(ctor),
                ctor.end.map { it.line }.orElse(0),
                imports, fields, file, fileHash,
                facetsOf(ctor, ctor.body),
            )
            chunks.add(chunk)
        }
    }

    private fun extractFromRecord(
        recordDecl: RecordDeclaration,
        packageName: String,
        imports: List<String>,
        file: File,
        fileHash: String,
        source: String,
        chunks: MutableList<MethodChunk>,
    ) {
        val className = recordDecl.fullyQualifiedName.orElse(
            if (packageName.isNotEmpty()) "$packageName.${recordDecl.nameAsString}"
            else recordDecl.nameAsString
        )


        val fields = recordDecl.parameters.take(20).map { it.toString().trim() }

        for (method in recordDecl.methods) {
            chunks.add(buildMethodChunk(
                className, packageName, method.nameAsString,
                method.declarationAsString, method.toString(),
                beginLine(method),
                method.end.map { it.line }.orElse(0),
                imports, fields, file, fileHash,
                facetsOf(method, method.body.orElse(null)),
            ))
        }

        for (ctor in recordDecl.constructors) {
            chunks.add(buildMethodChunk(
                className, packageName, "<init>",
                ctor.declarationAsString, ctor.toString(),
                beginLine(ctor),
                ctor.end.map { it.line }.orElse(0),
                imports, fields, file, fileHash,
                facetsOf(ctor, ctor.body),
            ))
        }
    }


    private fun fallbackChunks(file: File, source: String, fileHash: String, parsed: Boolean = false, astFqcn: String? = null): List<MethodChunk> {
        val packageName = PACKAGE_RE.find(source)?.groupValues?.get(1) ?: ""
        val fqcn: String
        val typeName: String
        if (astFqcn != null) {
            fqcn = astFqcn
            typeName = fqcn.substringAfterLast('.')
        } else {
            typeName = TYPE_DECL_RE.find(stripComments(source))?.groupValues?.get(1) ?: return emptyList()
            fqcn = if (packageName.isNotEmpty()) "$packageName.$typeName" else typeName
        }
        val nodeType = if (parsed) "JavaType" else "JavaFile"
        val descriptor = if (parsed) "no methods" else "unparsed source"
        System.err.println("JavaChunker: ${file.name} produced no method chunks ($descriptor); indexing as a $nodeType node ($fqcn)")

        val shortPackage = packageName.removePrefix("com.hypixel.hytale.")
        val embeddingText = buildString {
            appendLine("// Package: $shortPackage")
            appendLine("// Class: $typeName ($descriptor)")
            appendLine()
            append(source.take(FALLBACK_EMBED_CHARS))
        }

        return listOf(MethodChunk(
            id = fqcn,
            className = fqcn,
            packageName = packageName,
            methodName = "",
            methodSignature = "",
            content = source,
            filePath = file.path.replace('\\', '/'),
            fileHash = fileHash,
            lineStart = 1,
            lineEnd = source.count { it == '\n' } + 1,
            imports = emptyList(),
            fields = emptyList(),
            embeddingText = embeddingText,
            nodeType = nodeType,
        ))
    }

    private fun primaryTypeFqcn(cu: CompilationUnit): String? {
        val type = cu.primaryType.orElse(null) ?: cu.types.firstOrNull() ?: return null
        return type.fullyQualifiedName.orElse(null)
    }

    private fun stripComments(text: String): String =
        text.replace(Regex("(?s)/\\*.*?\\*/"), " ").replace(Regex("//[^\\n]*"), " ")

    private fun facetsOf(decl: CallableDeclaration<*>, body: BlockStmt?): MethodFacets {
        val visibility = when (decl.accessSpecifier) {
            AccessSpecifier.PUBLIC -> "public"
            AccessSpecifier.PRIVATE -> "private"
            AccessSpecifier.PROTECTED -> "protected"
            else -> "package"
        }
        val annotations = decl.annotations.map { it.name.identifier }
        return MethodFacets(
            visibility = visibility,
            isStatic = decl.isStatic,
            isAbstract = decl.isAbstract,
            annotations = annotations,
            thin = isThinBody(body),
        )
    }

    private fun isThinBody(body: BlockStmt?): Boolean {
        val statements = body?.statements ?: return false
        if (statements.size != 1) return false
        val stmt = statements.first()
        if (stmt.isReturnStmt) {
            return stmt.asReturnStmt().expression.map { it.isMethodCallExpr }.orElse(false)
        }
        if (stmt.isExpressionStmt) {
            return stmt.asExpressionStmt().expression.isMethodCallExpr
        }
        return false
    }

    private fun buildMethodChunk(
        className: String,
        packageName: String,
        methodName: String,
        signature: String,
        content: String,
        lineStart: Int,
        lineEnd: Int,
        imports: List<String>,
        fields: List<String>,
        file: File,
        fileHash: String,
        facets: MethodFacets,
    ): MethodChunk {
        val simpleClass = className.substringAfterLast('.')
        val id = "$className#$methodName"

        val shortPackage = packageName.removePrefix("com.hypixel.hytale.")

        val embeddingText = buildString {
            appendLine("// Package: $shortPackage")
            appendLine("// Class: $simpleClass")
            if (fields.isNotEmpty()) {
                appendLine("// Fields: ${fields.take(8).joinToString(", ")}")
            }
            appendLine("// Method: $methodName")
            appendLine()
            appendLine(signature)
            appendLine()
            append(content)
        }

        return MethodChunk(
            id = id,
            className = className,
            packageName = packageName,
            methodName = methodName,
            methodSignature = signature,
            content = content,
            filePath = file.path.replace('\\', '/'),
            fileHash = fileHash,
            lineStart = lineStart,
            lineEnd = lineEnd,
            imports = imports,
            fields = fields,
            embeddingText = embeddingText,
            facets = facets,
        )
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

data class MethodChunk(
    val id: String,
    val className: String,
    val packageName: String,
    val methodName: String,
    val methodSignature: String,
    val content: String,
    val filePath: String,
    val fileHash: String,
    val lineStart: Int,
    val lineEnd: Int,
    val imports: List<String>,
    val fields: List<String>,
    val embeddingText: String,
    val nodeType: String = "JavaMethod",
    val facets: MethodFacets? = null,
) {
    val visibility: String? get() = facets?.visibility
    val isStatic: Boolean get() = facets?.isStatic ?: false
    val isAbstract: Boolean get() = facets?.isAbstract ?: false
    val annotations: List<String> get() = facets?.annotations ?: emptyList()
    val thin: Boolean get() = facets?.thin ?: false
}

data class MethodFacets(
    val visibility: String,
    val isStatic: Boolean,
    val isAbstract: Boolean,
    val annotations: List<String>,
    val thin: Boolean,
)
