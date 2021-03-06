package io.provenance.aggregate.service.stream.extractors

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Abstract extractor that writes output to a file.
 *
 * @property name The base name of the file generated by this extractor.
 * @property suffix An optional suffix to append to the name of the generated file.
 */
abstract class FileExtractor(override val name: String, suffix: String? = null) :
    Extractor, Closeable, AutoCloseable {

    /**
     * The file output is written to.
     */
    protected val outputFile: Path = Files.createTempFile("${name}-", suffix)

    /**
     * The stream wrapping [outputFile]
     */
    protected val outputStream: OutputStream =
        BufferedOutputStream(Files.newOutputStream(outputFile, StandardOpenOption.APPEND, StandardOpenOption.WRITE))

    override fun output(): OutputType = OutputType.FilePath(outputFile)

    override fun close() {
        try {
            Files.deleteIfExists(outputFile)
        } catch (e: IOException) {
        }
    }
}