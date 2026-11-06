package datastructrue.iocd.unit;

import cn.edu.fudan.utils.CommonUtils;
import datastructrue.Pair;
import lombok.Getter;
import lombok.Setter;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;

@Getter
@Setter
public class SinkRecord {

    public String sinkClassName;
    public MethodRecord sinkMethod;
    public String jimpleUnitInfo;
    public HashSet<Pair<Integer, FieldRecord>> sinkField = new HashSet<>();

    public FieldRecord sinkClassBelongToF;
    public FieldRecord sinkMethodNameF;
    public FieldRecord sinkMethodF;
    public FieldRecord sinkProxyInvokeType; //记录是哪个代理，切换调用对象的时候需要更换这个值
    public String trigger = "NONE";  // NONE: 未识别出限制/没有限制 ; getter ; setter
    public LinkedHashSet<FieldRecord> sinkFilePathF = new LinkedHashSet<>();
    public FieldRecord sinkFileContentF;

    public HashSet<String> getAllSinkFieldType(String gdtType) {

        HashSet<String> sinkFldSigSet = getAllSinkFieldSig(gdtType);
        HashSet<String> sinkFldTypeSet = new HashSet<>();
        for (String sig : sinkFldSigSet) {
            sinkFldTypeSet.add(CommonUtils.getFieldTypeBySig(sig));
        }
        return sinkFldTypeSet;

    }

    public HashSet<String> getAllSinkFieldSig(String gdtType) {

        HashSet<String> sinkFldSigSet = new HashSet<>();
        if (gdtType.equals("INVOKE")) {
            if (this.sinkMethodF != null) {
                sinkFldSigSet.add(this.sinkMethodF.getSig());
            }
            if (this.sinkMethodNameF != null) {
                sinkFldSigSet.add(this.sinkMethodNameF.getSig());
            }
            if (this.sinkClassBelongToF != null) {
                sinkFldSigSet.add(this.sinkClassBelongToF.getSig());
            }
        } else if (gdtType.equals("FILE")) {
            for (FieldRecord tmpFR : this.getSinkFilePathF()) {
                sinkFldSigSet.add(tmpFR.getSig());
            }
            if (this.sinkFileContentF != null) sinkFldSigSet.add(this.sinkFileContentF.getSig());
        }
        return sinkFldSigSet;

    }

}
