package cn.edu.fudan.datastructure;

import java.io.Serializable;
import java.lang.reflect.Method;

import cn.edu.fudan.utils.CommonUtils;

public class OracleClassA implements Comparable, Serializable, OracleInterface{

        // the value the class had before fireStack was added, so that saved payloads stay readable
        private static final long serialVersionUID = 5512991639594175870L;

        public static int feedBackCode = -1;
        // call stacks of the probe calls of the current execution (the first few), used to check that
        // the sink was reached along the IOCD chain; cleared before each execution
        public static final java.util.List<StackTraceElement[]> fireStacks = new java.util.ArrayList<>();
        public String oracleField;
        public String anOracleMethod;

        private static void fire() {
            feedBackCode = 1;
            if (fireStacks.size() < 8) {
                fireStacks.add(new Throwable().getStackTrace());
            }
        }

        @Override
        public void anOracleMethod() {
            fire();
        }

        public String getOracleField() {
            fire();
            return oracleField;
        }

        public String getAnOracleMethod() {
            fire();
            return "finished";
        }

        /**
         * A probe reached as a JavaBean property rather than as a method call.
         * <p>
         * Rhino's JavaMembers only builds a BeanProperty for "xxx" when no member of that name already
         * exists (JavaMembers.reflect: a non-private member of the bean property's name makes it skip the
         * property), and only a BeanProperty takes the MemberBox.invoke branch of JavaMembers.get. The
         * property names of getOracleField and getAnOracleMethod are both taken by the public fields
         * oracleField / anOracleMethod, so neither can be reached that way. This getter's property name
         * ("oracleProbe") has no field, so it is reachable. Nothing else reads it.
         */
        public String getOracleProbe() {
            fire();
            return "finished";
        }

        public static String ofOracleProbePropertyName() {
            return "oracleProbe";
        }

        public void setOracleField(String oracleField) {
            fire();
        }

        public int hashCode() {
            return 1;
        }

        public boolean equals(Object obj) {
            return true;
        }

        @Override
        public int compareTo(Object o) {
            return 0;
        }

        public static Object randomProperty() {
            if (CommonUtils.generateWithProbability(0.9)) {
                if (CommonUtils.generateWithProbability(0.7)) {
                    if (CommonUtils.generateWithProbability(0.5)) {
                        return "oracleField";
                    } else {
                        return "anOracleMethod";
                    }
                } else {
                    return "anOracleMethod";
                }
            } else {
                return "cn.edu.fudan.datastructure.OracleClassA";
            }
        }

        public static Object ofOracleClassA() {
            return new OracleClassA();
        }

        public static Method ofOracleMethod() {
            try {
                return OracleClassA.class.getMethod("anOracleMethod");
            } catch (NoSuchMethodException e) {
                e.printStackTrace();
            }
            return null;
        }

        public static Method ofGetOracleField() {
            try {
                return OracleClassA.class.getMethod("getOracleField");
            } catch (NoSuchMethodException e) {
                e.printStackTrace();
            }
            return null;
        }

        public static String ofOracleFieldName() {
            return "oracleField";
        }
}
