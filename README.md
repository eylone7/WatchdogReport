# WatchdogReport
## About:
WatchdogReport is a minecraft plugin that support's 1.8.8 Paper/Spigot/Bukkit Servers Made By Herex_777.
This plugin is a Remake of a popular mc Server Report System.

## Features:
- Report GUI ✔
- Punishements commands ✔
- Like Hypixel ✔
- Watchdog Commands.... [You can Check Them]

## Pictures:
![LOG](https://i.imgur.com/Cry83C7.png)
![LOG](https://i.imgur.com/JdkMB8b.png)
![LOG](https://i.imgur.com/pqTNqoT.png)
![LOLG](https://proxy.spigotmc.org/f9fb50322da8c2939562b57527f8934d6142c908/687474703a2f2f696d672e736869656c64732e696f2f62616467652f76657273696f6e2d312e302e302d696e666f726d6174696f6e616c)

## Usage:
You Must Connect The MysQL Database or the plugin will not work.  
**I will add SQLite Storage System Support + Config File and Messages Manager Soon...**

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
