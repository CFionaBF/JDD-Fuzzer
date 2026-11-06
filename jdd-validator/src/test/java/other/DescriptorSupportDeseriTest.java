package other;

import cn.edu.fudan.exception.validator.ReflectionException;
import cn.edu.fudan.utils.ClassUtils;
import cn.edu.fudan.utils.ReflectionUtils;
// import org.apache.commons.collections.map.FixedSizeSortedMap;

import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;

public class DescriptorSupportDeseriTest {

    public static void main(String[] args) throws ReflectionException, IOException, ClassNotFoundException {
        Object deserializedObject = ReflectionUtils.createWithNoArgsConstructor(ClassUtils.getClassByName("javax.management.modelmbean.DescriptorSupport"));
        Map descriptorMap = (Map)ReflectionUtils.createWithNoArgsConstructor(ClassUtils.getClassByName("org.apache.commons.collections.map.FixedSizeSortedMap"));
        Map collectionMap = (Map)ReflectionUtils.createWithNoArgsConstructor(ClassUtils.getClassByName("java.util.Collections$CheckedMap"));
        Map innerMap = new HashMap();
        innerMap.put("targetObject", "value");
        ReflectionUtils.setFieldValue(collectionMap, "m", innerMap);
        ReflectionUtils.setFieldValue(descriptorMap, "map", collectionMap);
        ReflectionUtils.setFieldValue(deserializedObject, "descriptorMap", descriptorMap);

        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream);
        objectOutputStream.writeObject(deserializedObject);

        byte[] bytes = byteArrayOutputStream.toByteArray();

        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(bytes);
        ObjectInputStream objectInputStream = new ObjectInputStream(byteArrayInputStream);
        objectInputStream.readObject();



    }

}
