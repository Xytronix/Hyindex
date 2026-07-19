// Copyright 2026 Hyve. All rights reserved.
package com.hyve.knowledge.extraction

import com.github.javaparser.ParseProblemException
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.Modifier
import com.github.javaparser.ast.body.CallableDeclaration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.EnumDeclaration
import com.github.javaparser.ast.expr.InstanceOfExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.visitor.VoidVisitorAdapter
import com.github.javaparser.symbolsolver.JavaSymbolSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver
import com.hyve.knowledge.core.db.KnowledgeDatabase
import com.hyve.knowledge.core.logging.LogProvider
import com.hyve.knowledge.core.logging.StdoutLogProvider
import java.io.File


object JavaExtractor {


    fun extractAndStore(
        chunks: List<MethodChunk>,
        db: KnowledgeDatabase,
        sourceDir: File,
        log: LogProvider = StdoutLogProvider,
    ) {

        val typeSolver = CombinedTypeSolver(
            ReflectionTypeSolver(),
            JavaParserTypeSolver(sourceDir),
        )
        StaticJavaParser.getParserConfiguration().setSymbolResolver(JavaSymbolSolver(typeSolver))

        val counters = Counters()

        val packageNodes = mutableSetOf<String>()
        val classNodes = mutableMapOf<String, ClassInfo>()

        for (chunk in chunks) {
            packageNodes.add(chunk.packageName)
            if (chunk.className !in classNodes) {
                classNodes[chunk.className] = ClassInfo(
                    className = chunk.className,
                    packageName = chunk.packageName,
                    filePath = chunk.filePath,
                )
            }
        }


        val fileToClasses = chunks.groupBy { it.filePath }.keys
        val classRelations = mutableListOf<ClassRelation>()
        val callRelations = mutableListOf<CallRelation>()
        val typeCheckRelations = mutableListOf<TypeCheckRelation>()
        val typeModifiers = mutableMapOf<String, String>()

        for (filePath in fileToClasses) {
            try {
                val file = File(filePath)
                if (!file.exists()) continue
                val cu = StaticJavaParser.parse(file.readText())
                val relPath = file.relativeTo(sourceDir).path.replace('\\', '/')

                cu.accept(object : VoidVisitorAdapter<Void>() {
                    override fun visit(n: ClassOrInterfaceDeclaration, arg: Void?) {
                        val pkg = cu.packageDeclaration.map { it.nameAsString }.orElse("")
                        val fqcn = n.fullyQualifiedName.orElse(if (pkg.isNotEmpty()) "$pkg.${n.nameAsString}" else n.nameAsString)

                        if (n.hasModifier(Modifier.Keyword.SEALED)) typeModifiers[fqcn] = "sealed"
                        else if (n.hasModifier(Modifier.Keyword.NON_SEALED)) typeModifiers[fqcn] = "non-sealed"

                        for (ext in n.extendedTypes) {
                            classRelations.add(ClassRelation(fqcn, ext.nameAsString, "EXTENDS", relPath))
                        }

                        for (impl in n.implementedTypes) {
                            classRelations.add(ClassRelation(fqcn, impl.nameAsString, "IMPLEMENTS", relPath))
                        }

                        for (perm in n.permittedTypes) {
                            classRelations.add(ClassRelation(fqcn, perm.nameAsString, "PERMITS", relPath))
                        }

                        for (method in n.methods) collectCalls(fqcn, method, relPath, callRelations, counters, typeCheckRelations)
                        for (ctor in n.constructors) collectCalls(fqcn, ctor, relPath, callRelations, counters, typeCheckRelations)

                        super.visit(n, arg)
                    }

                    override fun visit(n: EnumDeclaration, arg: Void?) {
                        val pkg = cu.packageDeclaration.map { it.nameAsString }.orElse("")
                        val fqcn = n.fullyQualifiedName.orElse(if (pkg.isNotEmpty()) "$pkg.${n.nameAsString}" else n.nameAsString)

                        for (impl in n.implementedTypes) {
                            classRelations.add(ClassRelation(fqcn, impl.nameAsString, "IMPLEMENTS", relPath))
                        }

                        for (method in n.methods) collectCalls(fqcn, method, relPath, callRelations, counters, typeCheckRelations)
                        for (ctor in n.constructors) collectCalls(fqcn, ctor, relPath, callRelations, counters, typeCheckRelations)

                        super.visit(n, arg)
                    }
                }, null)
            } catch (e: ParseProblemException) {

            } catch (e: Exception) {
                System.err.println("Graph extraction error for $filePath: ${e.message}")
            }
        }


        db.inTransaction { conn ->

            val pkgPs = conn.prepareStatement(
                "INSERT OR IGNORE INTO nodes (id, node_type, display_name) VALUES (?, 'Package', ?)"
            )
            for (pkg in packageNodes) {
                if (pkg.isBlank()) continue
                pkgPs.setString(1, "pkg:$pkg")
                pkgPs.setString(2, pkg)
                pkgPs.addBatch()
            }
            pkgPs.executeBatch()


            val classPs = conn.prepareStatement(
                "INSERT OR IGNORE INTO nodes (id, node_type, display_name, file_path, owning_file, metadata) VALUES (?, 'JavaClass', ?, ?, ?, ?)"
            )
            for ((fqcn, info) in classNodes) {
                classPs.setString(1, "class:$fqcn")
                classPs.setString(2, fqcn.substringAfterLast('.'))
                classPs.setString(3, info.filePath)
                val relPath = File(info.filePath).relativeTo(sourceDir).path.replace('\\', '/')
                classPs.setString(4, relPath)
                classPs.setString(5, typeModifiers[fqcn]?.let { "{\"modifier\":\"$it\"}" })
                classPs.addBatch()
            }
            classPs.executeBatch()


            val containsPs = conn.prepareStatement(
                "INSERT OR IGNORE INTO edges (source_id, target_id, edge_type, owning_file_id) VALUES (?, ?, 'CONTAINS', ?)"
            )
            for ((fqcn, info) in classNodes) {
                if (info.packageName.isBlank()) continue
                containsPs.setString(1, "pkg:${info.packageName}")
                containsPs.setString(2, "class:$fqcn")
                val relPath = File(info.filePath).relativeTo(sourceDir).path.replace('\\', '/')
                containsPs.setString(3, relPath)
                containsPs.addBatch()
            }
            containsPs.executeBatch()


            val relPs = conn.prepareStatement(
                "INSERT OR IGNORE INTO edges (source_id, target_id, edge_type, owning_file_id, target_resolved) VALUES (?, ?, ?, ?, ?)"
            )
            for (rel in classRelations) {
                relPs.setString(1, "class:${rel.sourceClass}")

                val resolvedTarget = resolveClassName(rel.targetName, classNodes.keys)
                relPs.setString(2, "class:$resolvedTarget")
                relPs.setString(3, rel.relationType)
                relPs.setString(4, rel.owningFile)
                relPs.setInt(5, if (resolvedTarget == rel.targetName) 0 else 1)
                relPs.addBatch()
            }
            relPs.executeBatch()


            val declaredInPs = conn.prepareStatement(
                "INSERT OR IGNORE INTO edges (source_id, target_id, edge_type, owning_file_id) VALUES (?, ?, 'DECLARED_IN', ?)"
            )
            for (chunk in chunks) {
                if (chunk.methodName.isBlank()) continue
                if ('#' !in chunk.id) continue
                declaredInPs.setString(1, chunk.id)
                declaredInPs.setString(2, "class:${chunk.className}")
                val relPath = File(chunk.filePath).relativeTo(sourceDir).path.replace('\\', '/')
                declaredInPs.setString(3, relPath)
                declaredInPs.addBatch()
            }
            declaredInPs.executeBatch()


            val callsPs = conn.prepareStatement(
                "INSERT OR IGNORE INTO edges (source_id, target_id, edge_type, owning_file_id, target_resolved) VALUES (?, ?, 'CALLS', ?, ?)"
            )
            for (call in callRelations) {
                callsPs.setString(1, call.sourceMethodId)
                if (call.resolvedTargetId != null) {
                    callsPs.setString(2, call.resolvedTargetId)
                    callsPs.setString(3, call.owningFile)
                    callsPs.setInt(4, 1)
                } else {
                    val resolvedTarget = resolveClassName(call.targetName, classNodes.keys)
                    callsPs.setString(2, "class:$resolvedTarget")
                    callsPs.setString(3, call.owningFile)
                    callsPs.setInt(4, if (resolvedTarget == call.targetName) 0 else 1)
                }
                callsPs.addBatch()
            }
            callsPs.executeBatch()

            val instanceofPs = conn.prepareStatement(
                "INSERT OR IGNORE INTO edges (source_id, target_id, edge_type, owning_file_id, target_resolved) VALUES (?, ?, 'INSTANCEOF', ?, ?)"
            )
            for (tc in typeCheckRelations) {
                val simple = tc.typeName.substringAfterLast('.')
                val resolvedTarget = resolveClassName(simple, classNodes.keys)
                instanceofPs.setString(1, tc.sourceMethodId)
                instanceofPs.setString(2, "class:$resolvedTarget")
                instanceofPs.setString(3, tc.owningFile)
                instanceofPs.setInt(4, if (resolvedTarget == simple) 0 else 1)
                instanceofPs.addBatch()
            }
            instanceofPs.executeBatch()
        }

        log.info("CALLS resolution: method-precise=${counters.methodPrecise} class-granular=${counters.classGranular} errors=${counters.errors}")
    }

    private fun collectCalls(
        fqcn: String,
        callable: CallableDeclaration<*>,
        owningFile: String,
        out: MutableList<CallRelation>,
        counters: Counters,
        typeChecks: MutableList<TypeCheckRelation>,
    ) {
        val methodName = if (callable is ConstructorDeclaration) "<init>" else callable.nameAsString
        val sourceMethodId = "$fqcn#$methodName"

        for (creation in callable.findAll(ObjectCreationExpr::class.java)) {
            val resolvedId = try {
                val decl = creation.resolve()
                "${decl.declaringType().qualifiedName}#<init>"
            } catch (t: Throwable) {
                counters.errors++
                null
            }
            if (resolvedId != null) counters.methodPrecise++ else counters.classGranular++
            out.add(CallRelation(sourceMethodId, creation.type.nameAsString, owningFile, resolvedId))
        }
        for (invocation in callable.findAll(MethodCallExpr::class.java)) {
            val resolvedId = try {
                val decl = invocation.resolve()
                "${decl.declaringType().qualifiedName}#${decl.name}"
            } catch (t: Throwable) {
                counters.errors++
                null
            }
            if (resolvedId != null) {
                counters.methodPrecise++
                out.add(CallRelation(sourceMethodId, invocation.nameAsString, owningFile, resolvedId))
                continue
            }
            val scope = invocation.scope.orElse(null)
            if (scope is NameExpr) {
                counters.classGranular++
                out.add(CallRelation(sourceMethodId, scope.nameAsString, owningFile, null))
            }
        }
        for (check in callable.findAll(InstanceOfExpr::class.java)) {
            typeChecks.add(TypeCheckRelation(sourceMethodId, check.type.asString(), owningFile))
        }
    }


    private fun resolveClassName(simpleName: String, knownClasses: Set<String>): String {

        if ('.' in simpleName) return simpleName

        return knownClasses.firstOrNull { it.endsWith(".$simpleName") } ?: simpleName
    }

    private class Counters {
        var methodPrecise = 0
        var classGranular = 0
        var errors = 0
    }

    private data class ClassInfo(
        val className: String,
        val packageName: String,
        val filePath: String,
    )

    private data class ClassRelation(
        val sourceClass: String,
        val targetName: String,
        val relationType: String,
        val owningFile: String,
    )

    private data class CallRelation(
        val sourceMethodId: String,
        val targetName: String,
        val owningFile: String,
        val resolvedTargetId: String? = null,
    )

    private data class TypeCheckRelation(
        val sourceMethodId: String,
        val typeName: String,
        val owningFile: String,
    )
}
