package com.ubi.orm.driver;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.Map;

@Component
public class DriverFactory {
    private final Map<String, DatabaseDriver> drivers = new HashMap<>();

    @Autowired
    public DriverFactory(MySQLDriver mysqlDriver, MSSQLDriver mssqlDriver, SQLiteDriver sqliteDriver) {
        drivers.put("mysql", mysqlDriver);
        drivers.put("mssql", mssqlDriver);
        drivers.put("sqlite", sqliteDriver);
    }

    public DatabaseDriver getDriver(String driveType) {
        DatabaseDriver driver = drivers.get(driveType);
        if (driver == null) {
            throw new IllegalArgumentException("Unsupported driver: " + driveType);
        }
        return driver;
    }

    public void registerDriver(String type, DatabaseDriver driver) {
        drivers.put(type, driver);
    }
}
