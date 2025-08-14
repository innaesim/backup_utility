package com.db.utility.model;

import lombok.Data;

@Data
public class AppConfig {
    private String type;
    private String host;
    private Integer port;
    private String name;
    private String user;
    private String password;
    private String outputDir;
    private Boolean compress;
    private String filepattern="{db}_{date}.sql";
}