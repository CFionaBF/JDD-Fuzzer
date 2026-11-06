package cn.edu.fudan.generator.fielddependency;

import cn.edu.fudan.exception.validator.ReflectionException;
import cn.edu.fudan.utils.ReflectionUtils;
import datastructrue.iocd.unit.FieldRecord;

import java.lang.reflect.Array;

public class FieldDependencyUtils {
    public static String getDeepestFieldName(FieldRecord fieldRcd) {
        String fieldName = fieldRcd.getFieldName();
        FieldRecord tmpFieldRcd = fieldRcd;
        while (tmpFieldRcd.fields != null && !tmpFieldRcd.fields.isEmpty()) {
            tmpFieldRcd = tmpFieldRcd.fields.iterator().next();
            fieldName = tmpFieldRcd.getFieldName();
        }
        return fieldName;
    }

    public static Object getDeepestFieldValue(Object startObj, FieldRecord fieldRcd) {
        String fieldName = fieldRcd.getFieldName();
        String fieldTypeName = fieldRcd.getFieldType();
        FieldRecord tmpFieldRcd = fieldRcd;
        Object resObj = null;
        try {
            resObj = ReflectionUtils.getFieldValue(startObj, fieldName);
            if (resObj == null) return null; // if null is found in the middle, return null
            while (resObj != null && tmpFieldRcd.fields != null && !tmpFieldRcd.fields.isEmpty()) {
                tmpFieldRcd = tmpFieldRcd.fields.iterator().next();
                fieldName = tmpFieldRcd.getFieldName();
                fieldTypeName = tmpFieldRcd.getFieldType();
                if (resObj.getClass().isArray()) {
                    // if it is an array, get the first element
                    // and then get the field value
                    try {
                        resObj = Array.get(resObj, 0);
                    } catch (Exception | Error e) {
                        return null;
                    }
                    if (resObj == null) return null;
                    else resObj = ReflectionUtils.getFieldValue(resObj, fieldName);
                } else {
                    // if it is not an array, get the field value directly
                    resObj = ReflectionUtils.getFieldValue(resObj, fieldName);
                }
                if (resObj == null) return null;
            }
        } catch (ReflectionException e) {
            e.printStackTrace();
        }
        return resObj;
    }

}
