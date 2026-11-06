package cn.edu.fudan.generator.mutate;

import cn.edu.fudan.exception.validator.GeneratorException;
import cn.edu.fudan.exception.validator.ObjectNotFoundException;
import cn.edu.fudan.exception.validator.ReflectionException;
import cn.edu.fudan.generator.seed.SeedWrapper;
import cn.edu.fudan.utils.ClassUtils;
import cn.edu.fudan.utils.CommonUtils;
import cn.edu.fudan.utils.FuzzUtils;
import cn.edu.fudan.utils.LogUtils;
import cn.edu.fudan.utils.MutateUtils;
import datastructrue.Pair;
import datastructrue.iocd.unit.FieldRecord;
import java.util.HashSet;
import java.util.LinkedList;

public abstract class ExceptionBasedMutator {


    // the exception location has been handled
    private static HashSet<String> exceptionHandled = new HashSet<>();

    private static String getExceptionMsg(Throwable e) {
        StringBuilder sb = new StringBuilder();
        StackTraceElement[] tmpStack = e.getStackTrace();
        for (StackTraceElement sElement : tmpStack) {
            sb.append(sElement + "\n");
        }
        return sb.toString();
    }

    /**
     * Mainly for npe and cast exception
     *
     * @param seed
     * @param e
     */
    public static void handleException(SeedWrapper seed, Throwable e) throws ObjectNotFoundException, GeneratorException, ReflectionException, ClassNotFoundException {
        int crtIOCDHash = seed.iocdHash;
        String resMsg = getExceptionMsg(e);
        Pair<String, String> clzLineNumPair = MutateUtils.getExceptionClzLineNumPair(resMsg, crtIOCDHash);
        if (clzLineNumPair == null) return;
        LinkedList<FieldRecord> fldToMutateFRList = MutateUtils.getUsedFieldRecord(clzLineNumPair, crtIOCDHash);
        if (e instanceof NullPointerException) {
            if (exceptionHandled.contains(clzLineNumPair.getKey() + "#" + clzLineNumPair.getValue())) {
                return;
            }
            exceptionHandled.add(clzLineNumPair.getKey() + "#" + clzLineNumPair.getValue());
            // notify the generator() to fulfill the null value
            triggerNPEMutation(seed, fldToMutateFRList);
        } else if (e instanceof ClassCastException) {
            String needType = e.getMessage().split(" cannot be cast to ")[1];
            fldToMutateFRList.forEach(fldRcd -> {
                MutationObjectGenerator.crtSeedSpecificCastFiled.add(new CastFieldWrapper(fldRcd, needType));
            });
        } else {
            String exceptionDetails = CommonUtils.constructExceptionStackTrace(e);
            // e.g. an UndeclaredThrowableException from a proxy says nothing itself: show what it wraps
            for (Throwable c = e.getCause(); c != null && c != e; c = c.getCause() == c ? null : c.getCause()) {
                exceptionDetails += "Caused by: " + c + "\n" + CommonUtils.constructExceptionStackTrace(c);
            }
            LogUtils.logWarnOnce(e.getClass().getName() + " is ongoing, but not handled yet. the class-linenum info <" + clzLineNumPair.getKey() + "," + clzLineNumPair.getValue() + ">\n The exceptions details is below:\n" + exceptionDetails);
        }
    }

    /**
     * notify the MutationObjectGenerator start the routine of exception based mutation.
     * 
     * @param seed
     * @param list
     */
    private static void triggerNPEMutation (SeedWrapper seed, LinkedList<FieldRecord> list) {
        boolean isInterfaceFieldInvolved = false;
        for (FieldRecord fr : list) {
            String fldType = fr.getFieldType();
            // if the field is immutable, skip it.
            if (!seed.isMutatableFieldWithoutColTag(fr.classBelongTo, fr.classId, fr.fieldName)) continue;
            try {
                if (ClassUtils.isInterfaceByClzName(fldType)) {
                    isInterfaceFieldInvolved = true;
                }
                MutationObjectGenerator.crtSeedNotNullFiled.add(fr);
            } catch (Exception e) {
                LogUtils.warn("In `ExceptionBasedMutator.triggerNPEMutation`: Error when checking interface field type: " + fldType);
            }
        }
        MutationObjectGenerator.setNPEMutationFlag();
        if (isInterfaceFieldInvolved) MutationObjectGenerator.setExceptionMutationLeft(10);
    }

    public static class CastFieldWrapper {
        public FieldRecord fieldRecord;
        public String castType;

        public CastFieldWrapper(FieldRecord fieldRecord, String castType) {
            this.fieldRecord = fieldRecord;
            this.castType = castType;
        }

        @Override
        public int hashCode() {
            return fieldRecord.hashCode();
        }
    }
}
