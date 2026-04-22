package cn.niuyannet.kaochang.android.init;

import java.io.Serializable;
import java.util.List;

/**
 * {"mode":"custom","customTimes":[{"day":1,"enabled":true,"timeRange":["22:42:48","23:42:48"]},{"day":2,"enabled":true,"timeRange":["22:42:48","23:42:48"]},{"day":3,"enabled":true,"timeRange":["22:42:48","23:42:48"]},{"day":4,"enabled":true,"timeRange":["22:42:48","23:42:48"]},{"day":5,"enabled":true,"timeRange":["22:42:48","23:42:48"]},{"day":6,"enabled":true,"timeRange":["22:42:48","23:42:48"]},{"day":7,"enabled":true,"timeRange":["22:42:48","23:42:48"]}]}
 */
public class BusinessTime implements Serializable {
    public String mode;
    public List<String> defaultTime;
    public List<BusinessTimeItme> customTimes;
    public List<String> getDefaultTime() {
        return defaultTime;
    }

    public void setDefaultTime(List<String> defaultTime) {
        this.defaultTime = defaultTime;
    }



    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }


    public List<BusinessTimeItme> getCustomTimes() {
        return customTimes;
    }

    public void setCustomTimes(List<BusinessTimeItme> customTimes) {
        this.customTimes = customTimes;
    }


    public class BusinessTimeItme{
        public int day;
        public boolean enabled;
        public List<String> timeRange;
        public int getDay() {
            return day;
        }

        public void setDay(int day) {
            this.day = day;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getTimeRange() {
            return timeRange;
        }

        public void setTimeRange(List<String> timeRange) {
            this.timeRange = timeRange;
        }


    }
}
