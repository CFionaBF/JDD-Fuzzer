package cn.edu.fudan.data.distance.data;

public class InstNode {

    public String mtdName;
    public String mtdDesc;
    public int hash;
    public int label = -1;
    public int instNum = -1;
    public int lineNum = -1;

    public boolean isConditionInstMerge = false;
    public boolean isConditionBB = false;
    public int conditionHash = -1;

    public boolean isMethodEntry = false;
    public boolean isArriveMtdInstMerge = false;
    public boolean isArriveMtdBB = false;
    public int arrMtdHash = -1;

    public InstNode() {

    }

    public InstNode(String mtdName, String mtdDesc, int hash, int lineNum) {
        this.mtdName = mtdName.replace("'","");
        this.mtdDesc = mtdDesc.replace("'","");
        this.hash = hash;
        this.lineNum = lineNum;
    }

    public final int hashCode() {
        return this.hash;
    }

    public boolean isSameInstSite(String mtdName, String mtdDesc, int lineNum) {
        return this.mtdName.equals(mtdName)
                && this.mtdDesc.equals(mtdDesc)
                && this.lineNum == lineNum;
    }

}
