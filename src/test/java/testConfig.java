import com.ubi.orm.config.ConfigManager;
import com.ubi.orm.config.ConfigManagers;
import com.ubi.orm.monitor.log.LogUtils;

public class testConfig {
    public static void main(String[] args) throws Exception {
        try {
            ConfigManagers configManager = ConfigManagers.getInstance("f:/");
           LogUtils.coreDebug(configManager.getDbConfig().toString());
           LogUtils.coreDebug(configManager.getSqlConfig("user_modif").toString());
//           LogUtils.coreDebug(configManager.getEffectiveAuthConfig(configManager.getSqlConfig("user_search").getAuthConfig()).toString());
        }catch (Exception e){
            LogUtils.coreError(e.getMessage(),e);
        }
    }
}
