/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.caching.internal.tasks;

import com.google.common.collect.ImmutableList;
import org.apache.commons.io.FileUtils;
import org.apache.tools.tar.TarEntry;
import org.apache.tools.tar.TarOutputStream;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@State(Scope.Benchmark)
@SuppressWarnings("Since15")
public class TaskOutputPackagingBenchmark {
    Path tempDir;
    List<Path> inputFiles;
    OutputStream output;
    int fileCount = 100;
    int minFileSize = 1 * 1024;
    int maxFileSize = 32 * 1024;

    @Setup(Level.Trial)
    public void setupTrial() throws IOException {
        this.tempDir = Files.createTempDirectory("packaging-");
        Random random = new Random(1234L);
        ImmutableList.Builder<Path> inputFiles = ImmutableList.builder();
        for (int idx = 0; idx < fileCount; idx++) {
            Path inputFile = Files.createTempFile(tempDir, "input-", ".bin");
            int fileSize = minFileSize + random.nextInt(maxFileSize - minFileSize);
            byte[] buffer = new byte[fileSize];
            random.nextBytes(buffer);
            Files.write(inputFile, buffer);
            inputFiles.add(inputFile);
        }
        this.inputFiles = inputFiles.build();
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() throws IOException {
        FileUtils.forceDelete(tempDir.toFile());
    }

    @Setup(Level.Iteration)
    public void setupIteration() {
        this.output = new ByteArrayOutputStream(maxFileSize * fileCount);
    }

    @Benchmark
    public void packageTarGz(Blackhole bh) throws IOException {
        TarOutputStream output = new TarOutputStream(new GZIPOutputStream(this.output));
        for (Path inputFile : inputFiles) {
            TarEntry entry = new TarEntry(inputFile.getFileName().toString());
            entry.setSize(Files.size(inputFile));
            output.putNextEntry(entry);
            Files.copy(inputFile, output);
            output.closeEntry();
        }
        output.close();
    }

    @Benchmark
    public void packageJavaZip(Blackhole bh) throws IOException {
        ZipOutputStream output = new ZipOutputStream(this.output);
        for (Path inputFile : inputFiles) {
            ZipEntry entry = new ZipEntry(inputFile.getFileName().toString());
            output.putNextEntry(entry);
            Files.copy(inputFile, output);
            output.closeEntry();
        }
        output.close();
    }

    @Benchmark
    public void packageAntZip(Blackhole bh) throws IOException {
        org.apache.tools.zip.ZipOutputStream output = new org.apache.tools.zip.ZipOutputStream(this.output);
        for (Path inputFile : inputFiles) {
            org.apache.tools.zip.ZipEntry entry = new org.apache.tools.zip.ZipEntry(inputFile.getFileName().toString());
            output.putNextEntry(entry);
            Files.copy(inputFile, output);
            output.closeEntry();
        }
        output.close();
    }
}
