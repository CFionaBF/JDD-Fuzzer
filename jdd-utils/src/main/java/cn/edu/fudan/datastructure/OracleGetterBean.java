package cn.edu.fudan.datastructure;

import java.io.Serializable;

/**
 * Probe for sinks that call a bean's getters (e.g. fastjson2's ObjectWriter.write): a JavaBean whose only
 * property is a getter that calls the OracleClassA probe. OracleClassA itself does not fit: its getters have
 * public fields of the same names, which such serializers read instead of calling the getters.
 */
public class OracleGetterBean implements Serializable {
    private static final long serialVersionUID = 1L;

    public String getJddProbe() {
        new OracleClassA().anOracleMethod();
        return "finished";
    }
}
