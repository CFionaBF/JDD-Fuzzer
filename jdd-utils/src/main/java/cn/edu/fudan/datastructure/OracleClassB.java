package cn.edu.fudan.datastructure;

import com.sun.org.apache.xalan.internal.xsltc.DOM;
import com.sun.org.apache.xalan.internal.xsltc.TransletException;
import com.sun.org.apache.xalan.internal.xsltc.runtime.AbstractTranslet;
import com.sun.org.apache.xml.internal.dtm.DTMAxisIterator;
import com.sun.org.apache.xml.internal.serializer.SerializationHandler;

import java.io.IOException;
import java.io.Serializable;

public class OracleClassB  extends AbstractTranslet implements Serializable {
    public static boolean isFuzzingStart = false;
    public static int feedBackCode = -1;
    static {
        if (isFuzzingStart) {
            feedBackCode = 1;
            try {
                Runtime.getRuntime().exec("open /System/Applications/Calculator.app");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void transform(DOM document, SerializationHandler[] handlers) throws TransletException {

    }

    @Override
    public void transform(DOM document, DTMAxisIterator iterator, SerializationHandler handler) throws TransletException {

    }
}
