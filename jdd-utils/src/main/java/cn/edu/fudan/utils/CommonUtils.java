package cn.edu.fudan.utils;

import cn.edu.fudan.exception.inst.InvalidCFGStyleSigException;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class CommonUtils {

    static boolean fixRandomStringFlag = false;

    static String lastRandomString = "";

    // 要求类实现了反序列化接口的序列化/反序列化协议
    public static HashSet<String> needSerializedProtocolSet = new HashSet<>(
            Arrays.asList("jdk", "hessian-lite")
    );

    public static ArrayList<Character> CHARACTERS = new ArrayList<>(Arrays.asList(
            'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm',
            'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z'
    ));

    /**
     * @param protocol 协议名
     * @return 是否要求类实现序列接口
     */
    public static boolean isSerializableNeed(String protocol) {
        return needSerializedProtocolSet.contains(protocol);
    }

    /**
     * 该方法用于判断一个字符串是否符合CFG-Style的调用栈的格式
     *
     * @param line
     * @return
     */
    public static boolean isStackTraceLineValid(String line) {
        // 正则表达式用来匹配调用栈格式
        String regex = "^[\\w.$#]+#[\\w<>]+\\(\\).*";

        // 使用正则表达式进行匹配
        return line.matches(regex);
    }

    public static String constructExceptionStackTrace(Throwable e) {
        StringBuilder sb = new StringBuilder();
        if (e != null) {
            sb.append(e.getMessage() + " ");
            for (StackTraceElement trace : e.getStackTrace()) {
                sb.append(trace.toString()).append("\n");
            }
        }
        // LogUtil.warn("调用栈如下:" + sb.toString());
        return sb.toString();
    }

    /**
     * 在构建基本块和CFG过程中用到特殊的格式来描述一次方法调用，该方法用于做转换
     *
     * @param sootMtdSig
     * @return
     */
    public static String getCFGStyleMethodSigBySootSig(String sootMtdSig) throws InvalidCFGStyleSigException {

        String clzName = getDeclaredClzBySig(sootMtdSig);
        String mtdName = getMethodNameBySig(sootMtdSig);
        String mtdDescriptor = getMtdDescriptorBySubSig(getSubSigBySig(sootMtdSig));

        String res = clzName + "#" + mtdName + "():" + mtdDescriptor;
        if (!isStackTraceLineValid(res)) {
            throw new InvalidCFGStyleSigException("Invalid CFG Style Sig: " + res);
        }
        return res;

    }

    /**
     * 给定字符串形式的sootField sig，返回Sting类型的具体的类名
     *
     * @param fldSig sootField签名
     * @return field的类型
     */
    public static String getFieldTypeBySig(String fldSig) {

        String fieldType = "";
        String fldSubSig = getSubSigBySig(fldSig);
        fieldType = fldSubSig.split(" ")[0];

        return fieldType;

    }

    /**
     * 给定字符串形式的sootField sig，返回Sting类型的具体的fld名
     *
     * @param fldSig sootField签名
     * @return field名字
     */
    public static String getFieldNameBySig(String fldSig) {

        String fieldType = "";
        String fldSubSig = getSubSigBySig(fldSig);
        fieldType = fldSubSig.split(" ")[1];

        return fieldType;

    }

    /**
     * 给定一个方法的完整签名，返回不带括号的方法名
     *
     * @param mtdSig sootMethod签名
     * @return 不带括号的方法名
     */
    public static String getMethodNameBySig(String mtdSig) {

        //String mtdSubSig = getSubSigBySig(mtdSig);
        String fullMtdName = mtdSig.split(" ")[2];
        String mtdName = fullMtdName.split("\\(")[0];

        return mtdName;

    }

    /**
     * 给定一个方法的子签名，返回其描述符
     * e.g void readObject(java.lang.Object) -> (Ljava/lang/Object;)V
     *
     * @param subSig
     * @return
     */
    public static String getMtdDescriptorBySubSig(String subSig) {

        // Step1：处理返回值
        String resReturnType;
        int firstSpaceIndex = subSig.indexOf(" ");
        String originReturnType = subSig.substring(0, firstSpaceIndex);
        resReturnType = getFldDescriptorBySig(originReturnType);

        // Step2：处理参数
        StringBuilder resParamType = new StringBuilder();
        // 正则匹配到括号里的方法参数
        List<String> paramList = new ArrayList<>();
        String regex = "\\(([\\w\\W]*)\\)";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(subSig);
        while (matcher.find()) {
            paramList.add(matcher.group());
        }
        // 取出括号和空格，并用逗号分隔各个参数
        String tmpParamStr = paramList.get(0);
        tmpParamStr = tmpParamStr.replace("(", "");
        tmpParamStr = tmpParamStr.replace(")", "");
        tmpParamStr = tmpParamStr.replace(" ", "");
        String[] paramArr = tmpParamStr.split(",");
        // 遍历每个参数，根据对应的参数值进行转换
        resParamType.append("(");
        for (String paramStr : paramArr) {
            resParamType.append(getFldDescriptorBySig(paramStr));
        }
        resParamType.append(")");

        return resParamType.append(resReturnType).toString();
    }

    public static String getFldDescriptorBySig(String filedSig) {

        StringBuilder resSB = new StringBuilder();

        // 如果是空字符串说明没有传入任何的参数
        if (filedSig.equals("")) {
            return "";
        }

        // 判断是否是数组
        if (filedSig.contains("[")) {
            int oldCnt = filedSig.length();
            int newCnt = filedSig.replace("[", "").length();
            int num = oldCnt - newCnt;
            // 如果num不是0，说明返回值是数组，于是根据对应的数量添加[
            if (num != 0) {
                for (int i = 0; i < num; i++) {
                    resSB.append("[");
                }
            }
        }
        filedSig = filedSig.replace("[", "");
        filedSig = filedSig.replace("]", "");
        // 转换类型
        if (ClassUtils.primitiveTypeMap.containsKey(filedSig)) {
            resSB.append(ClassUtils.primitiveTypeMap.get(filedSig));
        } else {
            resSB.append("L");
            resSB.append(filedSig.replace(".", "/"));
            resSB.append(";");
        }

        return resSB.toString();
    }

    /**
     * 给定字符串形式的sootField/sootMethod sig，返回其subSig
     *
     * @param sig sootField/sootMethod签名
     * @return sootField/sootMethod的子签名
     */
    public static String getSubSigBySig(String sig) {

        String subSig = "";

        subSig = sig.replace("<", "");
        subSig = subSig.replace(">", "");
        subSig = subSig.split(": ")[1];

        return subSig;

    }

    // soot models a lambda as a class "<owner>$lambda_<name>_<n>__<id>" whose method calls the lambda body
    // <owner>.lambda$<name>$<n>; IOCD chains list both. The class does not exist at run time (the JVM's
    // lambda class has no class file and is a hidden frame on stacks), so such a gadget method has no
    // bytecode to analyze and never appears on a call stack.
    private static final Pattern SOOT_LAMBDA_CLASS = Pattern.compile("\\$lambda_.*__\\d+$");

    /** Whether a class name, or the class of a "<class: ...>" signature, is soot's class for a lambda. */
    public static boolean isSootLambdaClass(String clzOrSig) {
        if (clzOrSig == null) {
            return false;
        }
        String s = clzOrSig.replace("'", "").trim();
        if (s.startsWith("<") && s.indexOf(':') > 0) {
            s = s.substring(1, s.indexOf(':'));
        }
        return SOOT_LAMBDA_CLASS.matcher(s).find();
    }

    public static String getDeclaredClzBySig(String sig) {

        String decClz = "";
        decClz = sig.replace("<", "");
        decClz = decClz.replace(">", "");
        decClz = decClz.split(": ")[0];

        return decClz;

    }

    public static <T> T getLastItemOfLinkedHashSet(LinkedHashSet<T> set) {
        T res = null;
        Iterator iterator = set.iterator();
        while (iterator.hasNext()) {
            res = (T) iterator.next();
        }
        return res;
    }

    /**
     * 给定一个com.hn13.test.JustTest.main(JustTest.java:62)这样的调用栈中的字符串
     * 提取出当中的类名
     *
     * @param stackTrace
     * @return
     */
    public static String extractClassNameFromStackEntry(String stackTrace) {
        String regex = "^([\\w\\\\$.]+)\\.[^()]*\\(([^)]*)\\)$";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(stackTrace);

        if (matcher.matches()) {
            return matcher.group(1);
        }

        return null;
    }

    /**
     * 给定一个com.hn13.test.JustTest.main(JustTest.java:62)这样的调用栈中的字符串
     * 提取出当中的方法名
     *
     * @param str
     * @return
     */
    public static String extractMethodNameFromStackEntry(String str) {
        // 使用正则表达式匹配字符串
        Pattern pattern = Pattern.compile("^([\\w.]+\\.)+([\\w]+)\\(");
        Matcher matcher = pattern.matcher(str);

        // 如果找到匹配项，则返回方法名
        if (matcher.find()) {
            return matcher.group(2);
        } else {
            return null;
        }
    }

    public static int generateRandomInt(int min, int max) {
        if (min > max) {
            throw new IllegalArgumentException("min must be less than or equal to max");
        }

        Random random = new Random();
        return random.nextInt((max - min) + 1) + min;
    }

    public static double generateRandomDouble(double min, double max) {
        if (min > max) {
            throw new IllegalArgumentException("min must be less than or equal to max");
        }

        Random rand = new Random();
        return min + (max - min) * rand.nextDouble();
    }

    public static long generateRandomLong(long min, long max) {
        if (min > max) {
            throw new IllegalArgumentException("min must be less than or equal to max");
        }

        Random rand = new Random();
        return min + (long) (rand.nextDouble() * (max - min + 1));
    }

    public static float generateRandomFloat(float min, float max) {
        if (min > max) {
            throw new IllegalArgumentException("min must be less than or equal to max");
        }

        Random rand = new Random();
        return min + rand.nextFloat() * (max - min);
    }

    public static short generateRandomShort(short min, short max) {
        if (min > max) {
            throw new IllegalArgumentException("min must be less than or equal to max");
        }

        Random rand = new Random();
        return (short) (min + rand.nextInt((max - min) + 1));
    }

    /**
     * generate printable ascii char
     */
    public static char generateRandomChar() {
        Random rand = new Random();
        int randomNumber = rand.nextInt(95) + 32;
        return (char) randomNumber;
    }

    public static void fixRandomString() {
        fixRandomStringFlag = true;
    }

    public static void unfixRandomString() {
        fixRandomStringFlag = false;
    }

    public static String generateRandomString() {
        if (fixRandomStringFlag) {
            return lastRandomString;
        }
        Random random = new Random();
        int length = 3;
        StringBuilder sb = new StringBuilder(length);

        for (int i = 0; i < length; i++) {
            int index = random.nextInt(CHARACTERS.size());
            char randomChar = CHARACTERS.get(index);
            sb.append(randomChar);
        }

        lastRandomString = sb.toString();
        fixRandomStringFlag = true; // the random string seems useless, let fix it.

        return lastRandomString;
    }

    public static boolean generateRandomBoolean() {
        Random random = new Random();
        return random.nextBoolean();
    }

    /**
     * 从set中随机取值
     *
     * @param set 目标Set
     * @param <T> 目标示例类型
     * @return 随机取得的值
     */
    public static <T> T getRandomElementFromSet(Set<T> set) {
        if (set == null) {
            return null;
        }
        if (set.isEmpty()) {
            return null;
        }

        int randomIndex = generateRandomInt(0, set.size() - 1);
        Iterator<T> iterator = set.iterator();
        for (int i = 0; i < randomIndex; i++) {
            iterator.next();
        }

        return iterator.next();
    }

    public static byte[] transformInt2Bytes(int value) {
        byte[] bytes = new byte[4];
        bytes[3] = (byte) (value >> 24);  // 获取高位字节
        bytes[2] = (byte) (value >> 16);  // 获取次高位字节
        bytes[1] = (byte) (value >> 8);   // 获取次低位字节
        bytes[0] = (byte) value;          // 获取低位字节
        return bytes;
    }

    public static int transformBytes2Int(byte[] bytes) {
        if (bytes.length != 4) {
            throw new IllegalArgumentException("Invalid byte array length. Expected length: 4");
        }

        int value = ((bytes[3] & 0xFF) << 24) |
                ((bytes[2] & 0xFF) << 16) |
                ((bytes[1] & 0xFF) << 8) |
                (bytes[0] & 0xFF);

        return value;
    }

    public static <T> T getRandomItemFromList(List<T> list) {
        if (list.isEmpty()) {
            return null;
        }
        int index = generateRandomInt(0, list.size() - 1);
        return list.get(index);
    }

    public static <K, V> V getRandomValueFromMap(Map<K, V> map) {
        if (map.isEmpty()) {
            return null;
        }
        List<Map.Entry<K, V>> entryList = new ArrayList<>(map.entrySet());
        Map.Entry<K, V> entry = getRandomItemFromList(entryList);
        return entry.getValue();
    }

    public static boolean generateWith90PercentTrue() {
        Random RANDOM = new Random();
        return RANDOM.nextInt(100) < 90; // 0-89 为 true，90-99 为 false
    }

    /**
     * @param str 需要被进行判断的字符串
     * @return 是否以数字开头
     */
    public static boolean startWithNumber(String str) {
        return Pattern.matches("[0-9].*", str);
    }

    public static boolean nullSafeEquals(Object o1, Object o2) {
        if (o1 == o2) {
            return true;
        } else if (o1 != null && o2 != null) {
            if (o1.equals(o2)) {
                return true;
            } else {
                return o1.getClass().isArray() && o2.getClass().isArray() ? arrayEquals(o1, o2) : false;
            }
        } else {
            return false;
        }
    }

    private static boolean arrayEquals(Object o1, Object o2) {
        if (o1 instanceof Object[] && o2 instanceof Object[]) {
            return Arrays.equals((Object[]) ((Object[]) o1), (Object[]) ((Object[]) o2));
        } else if (o1 instanceof boolean[] && o2 instanceof boolean[]) {
            return Arrays.equals((boolean[]) ((boolean[]) o1), (boolean[]) ((boolean[]) o2));
        } else if (o1 instanceof byte[] && o2 instanceof byte[]) {
            return Arrays.equals((byte[]) ((byte[]) o1), (byte[]) ((byte[]) o2));
        } else if (o1 instanceof char[] && o2 instanceof char[]) {
            return Arrays.equals((char[]) ((char[]) o1), (char[]) ((char[]) o2));
        } else if (o1 instanceof double[] && o2 instanceof double[]) {
            return Arrays.equals((double[]) ((double[]) o1), (double[]) ((double[]) o2));
        } else if (o1 instanceof float[] && o2 instanceof float[]) {
            return Arrays.equals((float[]) ((float[]) o1), (float[]) ((float[]) o2));
        } else if (o1 instanceof int[] && o2 instanceof int[]) {
            return Arrays.equals((int[]) ((int[]) o1), (int[]) ((int[]) o2));
        } else if (o1 instanceof long[] && o2 instanceof long[]) {
            return Arrays.equals((long[]) ((long[]) o1), (long[]) ((long[]) o2));
        } else {
            return o1 instanceof short[] && o2 instanceof short[] ? Arrays.equals((short[]) ((short[]) o1), (short[]) ((short[]) o2)) : false;
        }
    }

    public static boolean generateWithProbability(double probabilityOfTrue) {
        if (probabilityOfTrue < 0 || probabilityOfTrue > 1) {
            throw new IllegalArgumentException("Probability must be between 0 and 1");
        }
        double randomValue = Math.random(); // Generate a random number between 0 (inclusive) and 1 (exclusive)
        return randomValue < probabilityOfTrue;
    }

    public static int nullSafeHashCode(Object obj) {
        if (obj == null) {
            return 0;
        } else {
            if (obj.getClass().isArray()) {
                if (obj instanceof Object[]) {
                    return nullSafeHashCode((Object[]) ((Object[]) obj));
                }

                if (obj instanceof boolean[]) {
                    return nullSafeHashCode((boolean[]) ((boolean[]) obj));
                }

                if (obj instanceof byte[]) {
                    return nullSafeHashCode((byte[]) ((byte[]) obj));
                }

                if (obj instanceof char[]) {
                    return nullSafeHashCode((char[]) ((char[]) obj));
                }

                if (obj instanceof double[]) {
                    return nullSafeHashCode((double[]) ((double[]) obj));
                }

                if (obj instanceof float[]) {
                    return nullSafeHashCode((float[]) ((float[]) obj));
                }

                if (obj instanceof int[]) {
                    return nullSafeHashCode((int[]) ((int[]) obj));
                }

                if (obj instanceof long[]) {
                    return nullSafeHashCode((long[]) ((long[]) obj));
                }

                if (obj instanceof short[]) {
                    return nullSafeHashCode((short[]) ((short[]) obj));
                }
            }

            return obj.hashCode();
        }
    }

    public static int nullSafeHashCode(Object[] array) {
        if (array == null) {
            return 0;
        } else {
            int hash = 7;
            Object[] var2 = array;
            int var3 = array.length;

            for (int var4 = 0; var4 < var3; ++var4) {
                Object element = var2[var4];
                hash = 31 * hash + nullSafeHashCode(element);
            }

            return hash;
        }
    }

    public static int nullSafeHashCode(boolean[] array) {
        if (array == null) {
            return 0;
        } else {
            int hash = 7;
            boolean[] var2 = array;
            int var3 = array.length;

            for (int var4 = 0; var4 < var3; ++var4) {
                boolean element = var2[var4];
                hash = 31 * hash + Boolean.hashCode(element);
            }

            return hash;
        }
    }

    public static int nullSafeHashCode(byte[] array) {
        if (array == null) {
            return 0;
        } else {
            int hash = 7;
            byte[] var2 = array;
            int var3 = array.length;

            for (int var4 = 0; var4 < var3; ++var4) {
                byte element = var2[var4];
                hash = 31 * hash + element;
            }

            return hash;
        }
    }

    public static int nullSafeHashCode(char[] array) {
        if (array == null) {
            return 0;
        } else {
            int hash = 7;
            char[] var2 = array;
            int var3 = array.length;

            for (int var4 = 0; var4 < var3; ++var4) {
                char element = var2[var4];
                hash = 31 * hash + element;
            }

            return hash;
        }
    }

    public static int nullSafeHashCode(double[] array) {
        if (array == null) {
            return 0;
        } else {
            int hash = 7;
            double[] var2 = array;
            int var3 = array.length;

            for (int var4 = 0; var4 < var3; ++var4) {
                double element = var2[var4];
                hash = 31 * hash + Double.hashCode(element);
            }

            return hash;
        }
    }

    public static int nullSafeHashCode(float[] array) {
        if (array == null) {
            return 0;
        } else {
            int hash = 7;
            float[] var2 = array;
            int var3 = array.length;

            for (int var4 = 0; var4 < var3; ++var4) {
                float element = var2[var4];
                hash = 31 * hash + Float.hashCode(element);
            }

            return hash;
        }
    }

    public static int nullSafeHashCode(int[] array) {
        if (array == null) {
            return 0;
        } else {
            int hash = 7;
            int[] var2 = array;
            int var3 = array.length;

            for (int var4 = 0; var4 < var3; ++var4) {
                int element = var2[var4];
                hash = 31 * hash + element;
            }

            return hash;
        }
    }

    public static int nullSafeHashCode(long[] array) {
        if (array == null) {
            return 0;
        } else {
            int hash = 7;
            long[] var2 = array;
            int var3 = array.length;

            for (int var4 = 0; var4 < var3; ++var4) {
                long element = var2[var4];
                hash = 31 * hash + Long.hashCode(element);
            }

            return hash;
        }
    }

    public static int nullSafeHashCode(short[] array) {
        if (array == null) {
            return 0;
        } else {
            int hash = 7;
            short[] var2 = array;
            int var3 = array.length;

            for (int var4 = 0; var4 < var3; ++var4) {
                short element = var2[var4];
                hash = 31 * hash + element;
            }

            return hash;
        }
    }

    public static int convertObjectToInt(Object obj) {
        if (obj instanceof Integer) {
            return (int) obj;
        } else if (obj instanceof String) {
            try {
                return Integer.parseInt((String) obj);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Cannot convert String to int: " + obj);
            }
        } else if (obj instanceof Number) {
            return ((Number) obj).intValue();
        } else {
            throw new IllegalArgumentException("Unsupported type for conversion to int: " + obj.getClass().getName());
        }
    }


}
