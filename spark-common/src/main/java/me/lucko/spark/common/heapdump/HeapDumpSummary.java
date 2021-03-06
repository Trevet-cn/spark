/*
 * This file is part of spark.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package me.lucko.spark.common.heapdump;

import com.google.gson.Gson;
import com.google.gson.stream.JsonWriter;
import me.lucko.spark.common.CommandSender;
import me.lucko.spark.common.util.TypeDescriptors;

import javax.management.JMX;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

/**
 * Represents a "heap dump summary" from the VM.
 *
 * <p>Contains a number of entries, corresponding to types of objects in the virtual machine
 * and their recorded impact on memory usage.</p>
 */
public final class HeapDumpSummary {

    /** The object name of the com.sun.management.DiagnosticCommandMBean */
    private static final String DIAGNOSTIC_BEAN = "com.sun.management:type=DiagnosticCommand";
    /** A regex pattern representing the expected format of the raw heap output */
    private static final Pattern OUTPUT_FORMAT = Pattern.compile("^\\s*(\\d+):\\s*(\\d+)\\s*(\\d+)\\s*([^\\s]+).*$");

    /**
     * Obtains the raw heap data output from the DiagnosticCommandMBean.
     *
     * @return the raw output
     * @throws Exception lots could go wrong!
     */
    private static String getRawHeapData() throws Exception {
        MBeanServer beanServer = ManagementFactory.getPlatformMBeanServer();
        ObjectName diagnosticBeanName = ObjectName.getInstance(DIAGNOSTIC_BEAN);

        DiagnosticCommandMXBean proxy = JMX.newMXBeanProxy(beanServer, diagnosticBeanName, DiagnosticCommandMXBean.class);
        return proxy.gcClassHistogram(new String[0]);
    }

    /**
     * Creates a new heap dump based on the current VM.
     *
     * @return the created heap dump
     * @throws RuntimeException if an error occurred whilst requesting a heap dump from the VM
     */
    public static HeapDumpSummary createNew() {
        String rawOutput;
        try {
            rawOutput = getRawHeapData();
        } catch (Exception e) {
            throw new RuntimeException("Unable to get heap dump", e);
        }

        return new HeapDumpSummary(Arrays.stream(rawOutput.split("\n"))
                .map(line -> {
                    Matcher matcher = OUTPUT_FORMAT.matcher(line);
                    if (!matcher.matches()) {
                        return null;
                    }

                    try {
                        return new Entry(
                                Integer.parseInt(matcher.group(1)),
                                Integer.parseInt(matcher.group(2)),
                                Long.parseLong(matcher.group(3)),
                                TypeDescriptors.getJavaType(matcher.group(4))
                        );
                    } catch (Exception e) {
                        e.printStackTrace();
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList()));
    }

    /** The entries in this heap dump */
    private final List<Entry> entries;

    private HeapDumpSummary(List<Entry> entries) {
        this.entries = entries;
    }

    private void writeOutput(JsonWriter writer, CommandSender creator) throws IOException {
        writer.beginObject();
        writer.name("type").value("heap");

        writer.name("metadata").beginObject();
        writer.name("user");
        new Gson().toJson(creator.toData().serialize(), writer);
        writer.endObject();

        writer.name("entries").beginArray();
        for (Entry entry : this.entries) {
            writer.beginObject();
            writer.name("#").value(entry.getOrder());
            writer.name("i").value(entry.getInstances());
            writer.name("s").value(entry.getBytes());
            writer.name("t").value(entry.getType());
            writer.endObject();
        }
        writer.endArray();
        writer.endObject();
    }

    public byte[] formCompressedDataPayload(CommandSender creator) {
        ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        try (Writer writer = new OutputStreamWriter(new GZIPOutputStream(byteOut), StandardCharsets.UTF_8)) {
            try (JsonWriter jsonWriter = new JsonWriter(writer)) {
                writeOutput(jsonWriter, creator);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return byteOut.toByteArray();
    }

    public static final class Entry {
        private final int order;
        private final int instances;
        private final long bytes;
        private final String type;

        Entry(int order, int instances, long bytes, String type) {
            this.order = order;
            this.instances = instances;
            this.bytes = bytes;
            this.type = type;
        }

        public int getOrder() {
            return this.order;
        }

        public int getInstances() {
            return this.instances;
        }

        public long getBytes() {
            return this.bytes;
        }

        public String getType() {
            return this.type;
        }
    }

    public interface DiagnosticCommandMXBean {
        String gcClassHistogram(String[] args);
    }

}
