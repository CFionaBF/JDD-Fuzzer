package datastructrue.inst;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The gadget chain a payload really ran to reach the sink, read off the call stack of a sink hit.
 * It can differ from the IOCD's chain (e.g. TiedMapEntry.hashCode() while a Hashtable is rebuilt
 * instead of the XStringForFSB.equals() call of the IOCD); either way it is a working chain, so the
 * fuzzer records each distinct one (Fuzzer.recordTriggeredChains) and runs are counted by them.
 *
 * Chain = the frames from the source (the method the deserializer called last, e.g. readObject or,
 * for hessian, Map.put) up to the sink call, entry first, as "class.method":
 *   - frames above the sink call (oracle/probe frames, the sink's own implementation) are cut;
 *   - JVM reflection internals (sun.reflect.*) are left out; java.lang.reflect calls are kept when
 *     made by a gadget, not when made by the deserializer (ObjectStreamClass.invokeReadObject);
 *   - generated class names are normalized ($Proxy12 -> $Proxy, $$Lambda$3/0x.. -> $$Lambda).
 * src-deploy/replay/ReplayCheck.java applies the same rules to the stacks of an independent replay.
 */
public final class TriggeredChain {

    // the stack was taken in File.<init> for a file the payload then wrote (the write call is not on it)
    public static final int HIT_FILE = 0;
    // taken in the INVOKE probe (OracleClassA), called through the chain's final Method.invoke
    public static final int HIT_PROBE = 1;
    // taken at the entry of an instrumented sink method (JDDFuzzRecord.doSinkArrivedFeedBack)
    public static final int HIT_SINK = 2;
    // taken at the entry of FileOutputStream.write for a payload file the execution then changed
    public static final int HIT_WRITE = 3;

    public static final String FILE_SINK = "java.io.FileOutputStream.write";

    private TriggeredChain() {
    }

    public static List<String> extract(StackTraceElement[] stack, int kind) {
        int top = 0;
        while (top < stack.length && isAboveSink(stack[top].getClassName(), kind)) {
            top++;
        }
        List<String> chain = new ArrayList<>();   // sink first, reversed at the end
        if (kind == HIT_FILE || kind == HIT_WRITE) {
            chain.add(FILE_SINK);
        }
        List<String> pendingReflection = new ArrayList<>();
        for (int i = top; i < stack.length; i++) {
            String clz = stack[i].getClassName();
            if (isDeserializerFrame(clz)) {
                break;  // the frame collected last is the source
            }
            if (clz.startsWith("sun.reflect.") || clz.startsWith("jdk.internal.reflect.")) {
                continue;
            }
            String frame = normalize(clz) + "." + stack[i].getMethodName();
            if (clz.startsWith("java.lang.reflect.") && !(i == top && kind == HIT_PROBE)) {
                // part of the chain only if a gadget made the call, see above
                pendingReflection.add(frame);
                continue;
            }
            chain.addAll(pendingReflection);
            pendingReflection.clear();
            chain.add(frame);
        }
        Collections.reverse(chain);
        return chain;
    }

    /** short stable id of a chain, used to tell chains apart across IOCDs, tries and runs */
    public static String key(List<String> chain) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(String.join("\n", chain).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(chain.hashCode());
        }
    }

    private static boolean isAboveSink(String clz, int kind) {
        if (clz.startsWith("datastructrue.inst.") || clz.startsWith("cn.edu.fudan.datastructure.")) {
            return true;    // the fuzzer's oracle code and the probe class
        }
        if (kind == HIT_PROBE) {
            return clz.startsWith("sun.reflect.") || clz.startsWith("jdk.internal.reflect.");
        }
        if (kind == HIT_FILE || kind == HIT_WRITE) {
            return clz.equals("java.io.File") || clz.equals("java.io.FileOutputStream");
        }
        return false;
    }

    static boolean isDeserializerFrame(String clz) {
        return clz.startsWith("java.io.ObjectInputStream") || clz.startsWith("java.io.ObjectStreamClass")
                || clz.contains("hessian.io.") || clz.contains("burlap.io.")
                || clz.startsWith("cn.edu.fudan.");    // the fuzzer itself, below the deserializer
    }

    static String normalize(String clz) {
        return clz.replaceAll("\\$Proxy\\d+", "\\$Proxy").replaceAll("\\$\\$Lambda\\$.*", "\\$\\$Lambda");
    }
}
