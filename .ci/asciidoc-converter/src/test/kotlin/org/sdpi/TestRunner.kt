package org.sdpi

import org.junit.jupiter.api.Assertions.assertEquals
import java.nio.file.Files

internal class TestRunner(val strTestFile: String) {

    public fun performTest() {

        val strSourceResource = "$strTestFile.adoc"
        val strExpectedOutputResource = "$strTestFile.html"

        val strExpectedOutput = readFileContents(strExpectedOutputResource)
        val strInput = readFileContents(strSourceResource)

        val tempOutputFile = Files.createTempFile("asciidoc-converter-test", ".tmp").toFile()
        val converter =
            AsciidocConverter(AsciidocConverter.Input.StringInput(strInput), tempOutputFile, generateTestOutput = true)

        converter.run()

        val strActualOutput = tempOutputFile.reader().readText()

        // Windows and Linux use different line endings but the output uses
        // \r line endings on Windows. Resolve this by normalizing both strings.
        val normalizedExpected = strExpectedOutput.lines().joinToString(System.lineSeparator())
        val normalizedOutput = strActualOutput.lines().joinToString(System.lineSeparator())
        assertEquals(normalizedExpected, normalizedOutput)

        tempOutputFile.delete()
    }

    private fun readFileContents(strPath: String): String {
        return javaClass.classLoader.getResourceAsStream(strPath)?.reader()?.readText()
            ?: throw Exception("Read failed")
    }
}