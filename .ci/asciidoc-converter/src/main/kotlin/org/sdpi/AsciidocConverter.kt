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
    private val outputFile: OutputStream,
    private val githubToken: String? = null,
    private val mode: Mode = Mode.Productive,
    private val dumpStructure: Boolean = false,
    private val generateTestOutput: Boolean = false, // skips some output when generating for tests.
) : Runnable {
    override fun run() {
        val options = Options.builder()
            .safe(SafeMode.UNSAFE)
            .backend(BACKEND)
            .sourcemap(true)
            .headerFooter(!generateTestOutput)
            .toStream(outputFile).build()
            //.toFile(outputFile).build()

        val asciidoctor = Asciidoctor.Factory.create()

        val anchorReplacements = AnchorReplacementsMap()

        // Formats sdpi_requirement blocks & their content.
        //   * RequirementBlockProcessor2 handles the containing sdpi_requirement block
        //   * RelatedBlockProcessor handles [RELATED] blocks within requirement blocks.
        //   * RequirementExampleBlockProcessor handles [EXAMPLE] blocks within requirement blocks.
        asciidoctor.javaExtensionRegistry().block(RequirementBlockProcessor2())
        asciidoctor.javaExtensionRegistry().block(RelatedBlockProcessor())
        asciidoctor.javaExtensionRegistry().block(RequirementExampleBlockProcessor())

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
        asciidoctor.javaExtensionRegistry().blockMacro(AddRequirementQueryPlaceholder())
        asciidoctor.javaExtensionRegistry().blockMacro(AddICSPlaceholder())
        asciidoctor.javaExtensionRegistry().treeprocessor(PopulateTables(infoCollector.info()))

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
        if (dumpStructure) {
            asciidoctor.javaExtensionRegistry().treeprocessor(DumpTreeInfo())
        }

        asciidoctor.requireLibrary("asciidoctor-diagram") // enables plantuml
        when (inputType) {
            is Input.FileInput -> asciidoctor.convertFile(inputType.file, options)
            is Input.StringInput -> asciidoctor.convert(inputType.string, options)
        }

        asciidoctor.shutdown()

        val jsonFormatter = Json { prettyPrint = true }

        if (!generateTestOutput) {
            writeArtifact("sdpi-requirements", jsonFormatter.encodeToString(infoCollector.info().requirements()))
            writeArtifact("sdpi-use-cases", jsonFormatter.encodeToString(infoCollector.info().useCases()))
        }
    }

    private fun writeArtifact(strArtifactName: String, strArtifact: String) {
        val referencedArtifactsFolder = "referenced-artifacts"
    /*    val path = Path.of(outputFile.parentFile.absolutePath, referencedArtifactsFolder)
        Files.createDirectories(path)
        Path.of(path.toFile().absolutePath, "${strArtifactName}.json").toFile().writeText(strArtifact)*/
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