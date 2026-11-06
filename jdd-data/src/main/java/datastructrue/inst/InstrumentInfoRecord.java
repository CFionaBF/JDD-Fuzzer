package datastructrue.inst;



import java.util.HashMap;
import java.util.HashSet;


public class InstrumentInfoRecord {
    // 类名 <-> 存在的IfStmt
    public HashMap<String, HashSet<IfStmtInstRecord>> classIfStmtsMap = new HashMap<>();

//    // call site记录
//    public HashMap<String, HashSet<CallSiteInstRecord>> classCallsitesMap = new HashMap<>();

    // 需要进入的方法插桩
    public HashMap<String, HashSet<MethodInstRecord>> classMethodsMap = new HashMap<>();
    // 判断gadget是否可利用的sink点
    public HashSet<SinkInstRecord> sinkRecords = new HashSet<>();

    public HashMap<String, HashSet<IfStmtInstRecord>> getClassIfStmtsMap() {
        return classIfStmtsMap;
    }

    public void setClassIfStmtsMap(HashMap<String, HashSet<IfStmtInstRecord>> classIfStmtsMap) {
        this.classIfStmtsMap = classIfStmtsMap;
    }

    public HashMap<String, HashSet<MethodInstRecord>> getClassMethodsMap() {
        return classMethodsMap;
    }

    public void setClassMethodsMap(HashMap<String, HashSet<MethodInstRecord>> classMethodsMap) {
        this.classMethodsMap = classMethodsMap;
    }

    public HashSet<SinkInstRecord> getSinkRecords() {
        return sinkRecords;
    }

    public void setSinkRecords(HashSet<SinkInstRecord> sinkRecords) {
        this.sinkRecords = sinkRecords;
    }
}
