# WatchdogReport
## About:
WatchdogReport is a minecraft plugin that support's 1.8.8 Paper/Spigot/Bukkit Servers Made By Herex_777.
This plugin is a Remake of a popular mc Server Report System.

## Features:
- Report System GUI Menu
- Punishements commands
- Watchdog Commands.... [You can Check Them]

## Pictures:
![LOG](https://i.imgur.com/Cry83C7.png)
![LOG](https://i.imgur.com/JdkMB8b.png)
![LOG](https://i.imgur.com/pqTNqoT.png)

## Usage:
You Must Connect The MysQL Database or the plugin will not work.  
[i will add SQLite Storage System Support Soon + Config File and Messages Manager]

You Can Edit MySQL Here:
```    private void connectToDatabase() {
        try {
            String url = "jdbc:mysql://host/db_831273?user=username&password=pass&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
            dbConnection = DriverManager.getConnection(url);
            getLogger().info("Successfully connected to MySQL database.");
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Failed to connect to MySQL", e);
            dbConnection = null;
        }
    }
```
