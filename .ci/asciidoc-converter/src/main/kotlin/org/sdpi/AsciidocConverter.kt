package org.sdpi

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.asciidoctor.Asciidoctor
import org.asciidoctor.Options
import org.asciidoctor.SafeMode
import org.sdpi.asciidoc.extension.*
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path

class AsciidocConverter(
    private val inputType: Input,
    private val outputFile: File,
    private val githubToken: String?,
    private val mode: Mode = Mode.Productive,
) : Runnable {
    override fun run() {
        val options = Options.builder()
            .safe(SafeMode.UNSAFE)
            .backend(BACKEND)
            .sourcemap(true)
            .toFile(outputFile).build()

        val asciidoctor = Asciidoctor.Factory.create()

        val anchorReplacements = AnchorReplacementsMap()

        // Formats sdpi_requirement blocks & their content.
        //   * RequirementBlockProcessor2 handles the containing sdpi_requirement block
        //   * RelatedBlockProcessor handles [RELATED] blocks within requirement blocks.
        //   * RequirementExampleBlockProcessor handles [EXAMPLE] blocks within requirement blocks.
        asciidoctor.javaExtensionRegistry().block(RequirementBlockProcessor2())
        asciidoctor.javaExtensionRegistry().block(RelatedBlockProcessor())
        asciidoctor.javaExtensionRegistry().block(RequirementExampleBlockProcessor())

        // Handle sdpi_requirement blocks.
        // Obsolete: now handled by RequirementBlockProcessor2
        //val requirementsListProcessor = RequirementListProcessor(requirementsBlockProcessor)

        asciidoctor.javaExtensionRegistry().treeprocessor(
            NumberingProcessor(
                when (mode) {
                    is Mode.Test -> mode.structureDump
                    else -> null
                },
                anchorReplacements
            )
        )

        // Gather bibliography entries.
        val bibliographyCollector = BibliographyCollector()
        asciidoctor.javaExtensionRegistry().treeprocessor(bibliographyCollector)

        // Gather SDPI specific information from the document such as
        // requirements and use-cases.
        val infoCollector = TreeInfoCollector(bibliographyCollector)
        asciidoctor.javaExtensionRegistry().treeprocessor(infoCollector)

        // Support to insert tables of requirements etc. sdpi_requirement_table macros.
        // Block macro processors insert placeholders that are populated when the tree is ready.
        // Tree processors fill in the placeholders.
        asciidoctor.javaExtensionRegistry().blockMacro(RequirementQuery_InsertPlaceholder())
        asciidoctor.javaExtensionRegistry().blockMacro(ICS_InsertPlaceholder())
        asciidoctor.javaExtensionRegistry().treeprocessor(QueryTable_Populater(infoCollector.info()))

        // Check requirement keywords (e.g., shall requirements include only shall).
        // Obsolete: now handled by SdpiInformationCollector.
        // asciidoctor.javaExtensionRegistry().treeprocessor(RequirementLevelProcessor())

        // Handle inline macros to cross-reference information from the document tree.
        asciidoctor.javaExtensionRegistry().inlineMacro(RequirementReferenceMacroProcessor(infoCollector.info()))
        asciidoctor.javaExtensionRegistry().inlineMacro(UseCaseReferenceMacroProcessor(infoCollector.info()))

        asciidoctor.javaExtensionRegistry().preprocessor(IssuesSectionPreprocessor(githubToken))
        asciidoctor.javaExtensionRegistry().preprocessor(DisableSectNumsProcessor())
        asciidoctor.javaExtensionRegistry().preprocessor(ReferenceSanitizerPreprocessor(anchorReplacements))
        asciidoctor.javaExtensionRegistry().postprocessor(ReferenceSanitizerPostprocessor(anchorReplacements))

        // Dumps tree of document structure to stdio.
        // Best not to use for very large documents!
        // Note: enabling this breaks variable replacement for {var_transaction_id}. Unclear why.
        // asciidoctor.javaExtensionRegistry().treeprocessor(DumpTreeInfo())

        //val processedInfoCollector = DocInfoCollector(bibliographyCollector)
        //asciidoctor.javaExtensionRegistry().docinfoProcessor(processedInfoCollector)

        //val postInfoCollector = DocInfoPostCollector(bibliographyCollector)
        //asciidoctor.javaExtensionRegistry().postprocessor(postInfoCollector)

        asciidoctor.requireLibrary("asciidoctor-diagram") // enables plantuml
        when (inputType) {
            is Input.FileInput -> asciidoctor.convertFile(inputType.file, options)
            is Input.StringInput -> asciidoctor.convert(inputType.string, options)
        }

        asciidoctor.shutdown()

        val jsonFormatter = Json { prettyPrint = true }

        writeArtifact("sdpi-requirements", jsonFormatter.encodeToString(infoCollector.info().requirements()))
        writeArtifact("sdpi-use-cases", jsonFormatter.encodeToString(infoCollector.info().useCases()))
    }

    private fun writeArtifact(strArtifactName : String, strArtifact : String)
    {
        val referencedArtifactsFolder = "referenced-artifacts"
        val path = Path.of(outputFile.parentFile.absolutePath, referencedArtifactsFolder)
        Files.createDirectories(path)
        Path.of(path.toFile().absolutePath, "${strArtifactName}.json").toFile().writeText(strArtifact)
    }

    private companion object {
        const val BACKEND = "html"
    }

    sealed interface Input {
        data class FileInput(val file: File) : Input
        data class StringInput(val string: String) : Input
    }

    sealed interface Mode {
        object Productive : Mode
        data class Test(val structureDump: OutputStream) : Mode
    }
}