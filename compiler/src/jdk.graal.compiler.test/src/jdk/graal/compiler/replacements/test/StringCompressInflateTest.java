/*
 * Copyright (c) 2018, 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package jdk.graal.compiler.replacements.test;

import static org.junit.Assume.assumeTrue;

import java.io.UnsupportedEncodingException;
import java.util.Arrays;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import jdk.graal.compiler.core.common.CompilationIdentifier;
import jdk.graal.compiler.hotspot.test.HotSpotGraalCompilerTest;
import jdk.graal.compiler.nodes.Invoke;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.StructuredGraph.AllowAssumptions;
import jdk.graal.compiler.replacements.StringLatin1InflateNode;
import jdk.graal.compiler.replacements.StringUTF16CompressNode;
import jdk.graal.compiler.replacements.amd64.AMD64GraphBuilderPlugins;
import jdk.graal.compiler.test.AddExports;
import jdk.vm.ci.code.InstalledCode;
import jdk.vm.ci.code.InvalidInstalledCodeException;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;

/**
 * Test intrinsic/node substitutions for (innate) methods StringLatin1.inflate and
 * StringUTF16.compress provided by {@link AMD64GraphBuilderPlugins}.
 */
@AddExports({"java.base/java.lang"})
public final class StringCompressInflateTest extends HotSpotGraalCompilerTest {

    static final int N = 1000;

    @Before
    public void checkCompactString() {
        ResolvedJavaType stringType = getMetaAccess().lookupJavaType(String.class);
        ResolvedJavaField compactStringsField = null;

        for (ResolvedJavaField f : stringType.getStaticFields()) {
            if (f.getName().equals("COMPACT_STRINGS")) {
                compactStringsField = f;
                break;
            }
        }

        assertTrue("String.COMPACT_STRINGS must be present", compactStringsField != null);
        JavaConstant compactStringsValue = getConstantReflection().readFieldValue(compactStringsField, null);
        assumeTrue("Skip StringCompressInflateTest with -XX:-UseCompactString", compactStringsValue.asBoolean());
    }

    @Test
    public void testStringLatin1Inflate() throws ClassNotFoundException, UnsupportedEncodingException {
        Class<?> javaclass = Class.forName("java.lang.StringLatin1");
        Class<?> testclass = StringLatin1InflateNode.class;

        TestMethods tms = new TestMethods("testInflate", javaclass, StringLatin1InflateNode.class, "inflate",
                        byte[].class, int.class, char[].class, int.class, int.class);

        tms.testSubstitution(testclass);

        for (int i = 0; i < N; i++) {
            byte[] src = fillLatinBytes(new byte[i2sz(i)]);
            char[] dst = new char[i2sz(i)];

            // Invoke void StringLatin1.inflate(byte[], 0, char[], 0, length)
            Object nil = tms.invokeJava(src, 0, dst, 0, i2sz(i));

            assert nil == null;

            // Perform a sanity check:
            for (int j = 0; j < i2sz(i); j++) {
                assert (dst[j] & 0xff00) == 0;
                assert (32 <= dst[j] && dst[j] <= 126) || (160 <= dst[j] && dst[j] <= 255);
                assert ((byte) dst[j] == src[j]);
            }

            String str = new String(src, 0, src.length, "ISO8859_1");

            for (int j = 0; j < src.length; j++) {
                assert ((char) src[j] & 0xff) == str.charAt(j);
            }

            // Invoke char[] testInflate(String)
            char[] inflate1 = (char[]) tms.invokeTest(str);

            // Another sanity check:
            for (int j = 0; j < i2sz(i); j++) {
                assert (inflate1[j] & 0xff00) == 0;
                assert (32 <= inflate1[j] && inflate1[j] <= 126) || (160 <= inflate1[j] && inflate1[j] <= 255);
            }

            assertDeepEquals(dst, inflate1);

            // Invoke char[] testInflate(String) through code handle.
            char[] inflate2 = (char[]) tms.invokeCode(str);
            assertDeepEquals(dst, inflate2);
        }
    }

    @Test
    public void testStringLatin1InflateByteByte() throws ClassNotFoundException {
        Class<?> javaclass = Class.forName("java.lang.StringLatin1");

        ResolvedJavaMethod caller = getResolvedJavaMethod(javaclass, "inflate", byte[].class, int.class, byte[].class, int.class, int.class);
        StructuredGraph graph = getIntrinsicGraph(caller, CompilationIdentifier.INVALID_COMPILATION_ID, getDebugContext(), AllowAssumptions.YES, null);
        assertInGraph(graph, StringLatin1InflateNode.class);

        InstalledCode code = getCode(caller, graph);

        for (int dstOffset = 0; dstOffset < 2; dstOffset++) {
            for (int srcOffset = 0; srcOffset < 2; srcOffset++) {
                for (int i = 0; i < N; i++) {
                    int length = i2sz(i);
                    byte[] src = fillLatinBytes(new byte[length]);
                    int resultLength = length * 2;
                    byte[] dst = new byte[resultLength];
                    int copiedLength = Math.max(0, length - Math.max(dstOffset, srcOffset));
                    int dstDelta = Math.min(dstOffset, copiedLength);
                    int srcDelta = Math.min(srcOffset, copiedLength);
                    invokeSafe(caller, null, src, srcDelta, dst, dstDelta, copiedLength);

                    // Perform a sanity check:
                    for (int j = 0; j < copiedLength; j++) {
                        assert (dst[j * 2 + 1 + dstDelta * 2]) == 0;
                        int c = dst[j * 2 + dstDelta * 2] & 0xFF;
                        assert (32 <= c && c <= 126) || (160 <= c && c <= 255);
                        assert (c == (src[j + srcDelta] & 0xFF));
                    }

                    byte[] dst2 = new byte[resultLength];
                    executeVarargsSafe(code, src, srcDelta, dst2, dstDelta, copiedLength);
                    assertDeepEquals(dst, dst2);
                }
            }
        }
    }

    @Test
    public void testStringLatin1InflateByteChar() throws ClassNotFoundException {
        Class<?> javaclass = Class.forName("java.lang.StringLatin1");

        ResolvedJavaMethod caller = getResolvedJavaMethod(javaclass, "inflate", byte[].class, int.class, char[].class, int.class, int.class);
        StructuredGraph graph = getIntrinsicGraph(caller, CompilationIdentifier.INVALID_COMPILATION_ID, getDebugContext(), AllowAssumptions.YES, null);
        assertInGraph(graph, StringLatin1InflateNode.class);

        InstalledCode code = getCode(caller, graph);

        for (int dstOffset = 0; dstOffset < 2; dstOffset++) {
            for (int srcOffset = 0; srcOffset < 2; srcOffset++) {
                for (int i = 0; i < N; i++) {
                    int length = i2sz(i);
                    byte[] src = fillLatinBytes(new byte[length]);
                    char[] dst = new char[length];
                    int copiedLength = Math.max(0, length - Math.max(dstOffset, srcOffset));
                    int dstDelta = Math.min(dstOffset, copiedLength);
                    int srcDelta = Math.min(srcOffset, copiedLength);
                    invokeSafe(caller, null, src, srcDelta, dst, dstDelta, copiedLength);

                    // Perform a sanity check:
                    for (int j = 0; j < copiedLength; j++) {
                        int c = dst[j + dstDelta] & 0xFF;
                        assert (32 <= c && c <= 126) || (160 <= c && c <= 255);
                        assert (c == (src[j + srcDelta] & 0xFF));
                    }

                    char[] dst2 = new char[length];
                    executeVarargsSafe(code, src, srcDelta, dst2, dstDelta, copiedLength);
                    assertDeepEquals(dst, dst2);
                }
            }
        }
    }

    @Test
    public void testStringUTF16Compress() throws ClassNotFoundException, UnsupportedEncodingException {
        Class<?> javaclass = Class.forName("java.lang.StringUTF16");
        Class<?> testclass = StringUTF16CompressNode.class;
        TestMethods tms = new TestMethods("testCompress", javaclass, StringUTF16CompressNode.class, "compress",
                        char[].class, int.class, byte[].class, int.class, int.class);
        tms.testSubstitution(testclass);

        for (int i = 0; i < N; i++) {
            char[] src = fillLatinChars(new char[i2sz(i)]);
            byte[] dst = new byte[i2sz(i)];

            // Invoke int StringUTF16.compress(char[], 0, byte[], 0, length)
            Object len = tms.invokeJava(src, 0, dst, 0, i2sz(i));

            assert (int) len == i2sz(i);

            // Invoke String testCompress(char[])
            String str1 = (String) tms.invokeTest(src);

            assertDeepEquals(dst, str1.getBytes("ISO8859_1"));

            // Invoke String testCompress(char[]) through code handle.
            String str2 = (String) tms.invokeCode(src);

            assertDeepEquals(dst, str2.getBytes("ISO8859_1"));
        }
    }

    @Test
    public void testStringUTF16CompressByteByte() throws ClassNotFoundException {
        Class<?> javaclass = Class.forName("java.lang.StringUTF16");

        ResolvedJavaMethod caller = getResolvedJavaMethod(javaclass, "compress", byte[].class, int.class, byte[].class, int.class, int.class);
        StructuredGraph graph = getIntrinsicGraph(caller, CompilationIdentifier.INVALID_COMPILATION_ID, getDebugContext(), AllowAssumptions.YES, null);
        assertInGraph(graph, StringUTF16CompressNode.class);

        InstalledCode code = getCode(caller, graph);

        for (int dstOffset = 0; dstOffset < 2; dstOffset++) {
            for (int srcOffset = 0; srcOffset < 2; srcOffset++) {
                for (int i = 0; i < N; i++) {
                    int length = i2sz(i);
                    byte[] src = fillLatinChars(new byte[length * 2]);
                    byte[] dst = new byte[length];
                    int copiedLength = Math.max(0, length - Math.max(dstOffset, srcOffset));
                    int dstDelta = Math.min(dstOffset, copiedLength);
                    int srcDelta = Math.min(srcOffset, copiedLength);
                    invokeSafe(caller, null, src, srcDelta, dst, dstDelta, copiedLength);

                    // Perform a sanity check:
                    for (int j = 0; j < copiedLength; j++) {
                        int c = dst[j + dstDelta] & 0xFF;
                        assert (32 <= c && c <= 126) || (160 <= c && c <= 255);
                        assert (c == (src[(j + srcDelta) * 2] & 0xFF));
                    }

                    byte[] dst2 = new byte[length];
                    executeVarargsSafe(code, src, srcDelta, dst2, dstDelta, copiedLength);
                    assertDeepEquals(dst, dst2);
                }
            }
        }
    }

    @Test
    public void testStringUTF16CompressCharByte() throws ClassNotFoundException {
        Class<?> javaclass = Class.forName("java.lang.StringUTF16");

        ResolvedJavaMethod caller = getResolvedJavaMethod(javaclass, "compress", char[].class, int.class, byte[].class, int.class, int.class);
        StructuredGraph graph = getIntrinsicGraph(caller, CompilationIdentifier.INVALID_COMPILATION_ID, getDebugContext(), AllowAssumptions.YES, null);
        assertInGraph(graph, StringUTF16CompressNode.class);

        InstalledCode code = getCode(caller, graph);

        for (int dstOffset = 0; dstOffset < 2; dstOffset++) {
            for (int srcOffset = 0; srcOffset < 2; srcOffset++) {
                for (int i = 0; i < N; i++) {
                    int length = i2sz(i);
                    char[] src = fillLatinChars(new char[length]);
                    byte[] dst = new byte[length];
                    int copiedLength = Math.max(0, length - Math.max(dstOffset, srcOffset));
                    int dstDelta = Math.min(dstOffset, copiedLength);
                    int srcDelta = Math.min(srcOffset, copiedLength);
                    invokeSafe(caller, null, src, srcDelta, dst, dstDelta, copiedLength);

                    // Perform a sanity check:
                    for (int j = 0; j < copiedLength; j++) {
                        int c = dst[j + dstDelta] & 0xFF;
                        assert (32 <= c && c <= 126) || (160 <= c && c <= 255);
                        assert (c == (src[j + srcDelta] & 0xFF));
                    }

                    byte[] dst2 = new byte[length];
                    executeVarargsSafe(code, src, srcDelta, dst2, dstDelta, copiedLength);
                    assertDeepEquals(dst, dst2);
                }
            }
        }

        // Exhaustively check compress returning the correct index of the non-latin1 char.
        final int size = 48;
        final byte fillByte = 'R';
        char[] chars = new char[size];
        final byte[] bytes = new byte[chars.length];
        Arrays.fill(bytes, fillByte);
        for (int i = 0; i < size; i++) { // Every starting index
            for (int j = i; j < size; j++) {  // Every location of non-latin1
                Arrays.fill(chars, 'A');
                chars[j] = 0xFF21;
                byte[] dst = Arrays.copyOf(bytes, bytes.length);
                byte[] dst2 = Arrays.copyOf(bytes, bytes.length);
                int result = (int) invokeSafe(caller, null, chars, i, dst, 0, chars.length - i);
                int result2 = (int) executeVarargsSafe(code, chars, i, dst2, 0, chars.length - i);
                Assert.assertEquals(result, result2);
                Assert.assertArrayEquals(dst, dst2);
                Assert.assertEquals("compress found wrong index", j - i, result);
                Assert.assertEquals("extra character stored", fillByte, bytes[j]);
            }
        }
    }

    public static void getCharsSnippet(String s, int srcBegin, int srcEnd, char[] dst, int dstBegin) {
        s.getChars(srcBegin, srcEnd, dst, dstBegin);
    }

    @Test
    public void testGetChars() throws InvalidInstalledCodeException {
        // Stress String inflation with large offsets
        String s = null;
        char[] dst = null;
        try {
            s = new String(new char[Integer.MAX_VALUE - 20]);
            dst = new char[s.length()];
        } catch (OutOfMemoryError e) {
        }
        // Use Assume so that we get a nice message that it wasn't actually run
        Assume.assumeTrue("not enough heap", s != null);
        Assume.assumeTrue("not enough heap", dst != null);
        InstalledCode code = getCode(getResolvedJavaMethod("getCharsSnippet"));
        int offset = 0;
        int size = 1024 * 1024;
        while (offset < s.length()) {
            if (offset + size < 0) {
                size = s.length() - offset;
            }
            code.executeVarargs(s, offset, offset + size, dst, offset);
            offset += size;
        }
    }

    @SuppressWarnings("all")
    public static String testCompress(char[] a) {
        return new String(a);
    }

    @SuppressWarnings("all")
    public static char[] testInflate(String a) {
        return a.toCharArray();
    }

    private class TestMethods {

        TestMethods(String testmname, Class<?> javaclass, Class<?> intrinsicClass, String javamname, Class<?>... params) {
            javamethod = getResolvedJavaMethod(javaclass, javamname, params);
            testmethod = getResolvedJavaMethod(testmname);
            testgraph = getIntrinsicGraph(javamethod, CompilationIdentifier.INVALID_COMPILATION_ID, getDebugContext(), AllowAssumptions.YES, null);
            assertInGraph(testgraph, intrinsicClass);

            assert javamethod != null;
            assert testmethod != null;

            // Force the test method to be compiled.
            testcode = getCode(testmethod);

            assert testcode != null;
        }

        StructuredGraph replacementGraph() {
            return getReplacements().getInlineSubstitution(javamethod, 0, false, Invoke.InlineControl.Normal, false, null, testgraph.allowAssumptions(), getInitialOptions());
        }

        StructuredGraph testMethodGraph() {
            return testgraph;
        }

        void testSubstitution(Class<?> intrinsicclass) {
            // Check if the resulting graph contains the expected node.
            if (replacementGraph() == null) {
                assertInGraph(testMethodGraph(), intrinsicclass);
            }
        }

        Object invokeJava(Object... args) {
            return invokeSafe(javamethod, null, args);
        }

        Object invokeTest(Object... args) {
            return invokeSafe(testmethod, null, args);
        }

        Object invokeCode(Object... args) {
            return executeVarargsSafe(testcode, args);
        }

        // Private data section:
        private ResolvedJavaMethod javamethod;
        private ResolvedJavaMethod testmethod;
        private StructuredGraph testgraph;
        private InstalledCode testcode;
    }

    private static byte[] fillLatinBytes(byte[] v) {
        for (int ch = 32, i = 0; i < v.length; i++) {
            v[i] = (byte) (ch & 0xff);
            ch = ch == 126 ? 160 : (ch == 255 ? 32 : ch + 1);
        }
        return v;
    }

    private static char[] fillLatinChars(char[] v) {
        for (int ch = 32, i = 0; i < v.length; i++) {
            v[i] = (char) (ch & 0xff);
            ch = ch == 126 ? 160 : (ch == 255 ? 32 : ch + 1);
        }
        return v;
    }

    private static byte[] fillLatinChars(byte[] v) {
        for (int ch = 32, i = 0; i < v.length; i += 2) {
            v[i] = (byte) (ch & 0xff);
            ch = ch == 126 ? 160 : (ch == 255 ? 32 : ch + 1);
        }
        return v;
    }

    private static int i2sz(int i) {
        return i * 3;
    }
}
