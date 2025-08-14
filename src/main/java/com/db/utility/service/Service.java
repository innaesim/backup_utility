package com.db.utility.service;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import com.db.utility.model.AppConfig;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Data
public class Service {
    private final AppConfig config;
    public Service(AppConfig config){
        this.config = config;
    }

    public void runBackup() throws IllegalArgumentException, IOException, InterruptedException{
        String dbType = config.getType().toLowerCase();
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String filename = config.getFilepattern()
            .replace("{db}", config.getName())
            .replace("{date}", timestamp);
        File outputDir = new File(config.getOutputDir());
        if (!outputDir.exists()) outputDir.mkdirs();

        String outputFilePath = outputDir.getAbsolutePath() + File.separator + filename;
        List<String> commands = new ArrayList<>();
        if("postgresql".equals(dbType)){
            commands.add("pg_dump");
            commands.add("-h"); commands.add(config.getHost());
            commands.add("-p"); commands.add(String.valueOf(config.getPort()));
            commands.add("-U"); commands.add(config.getUser());
            commands.add("-f"); commands.add(outputFilePath);
            commands.add(config.getName());
        }else if("mysql".equals(dbType)){
            commands.add("mysqldump");
            commands.add("-h"); commands.add(config.getHost());
            commands.add("-P"); commands.add(String.valueOf(config.getPort()));
            commands.add("-u"); commands.add(config.getUser());
            commands.add("-p" + config.getPassword());
            commands.add(config.getName());
        }else{
            throw new IllegalArgumentException("Unsupported Database Type : ".concat(dbType));
        }

        log.info("Executing backup...");
        try {
            ProcessBuilder pb = new ProcessBuilder(commands);
            pb.inheritIO();
            Process process = pb.start();

            if(Boolean.TRUE.equals(config.getCompress())){
                try (
                    InputStream in = process.getInputStream();
                    FileOutputStream fos = new FileOutputStream(outputFilePath);
                    BufferedOutputStream bos = new BufferedOutputStream(fos);
                    GZIPOutputStream gzipOut = new GZIPOutputStream(bos);
                    )
                {
                    byte[] buffer = new byte[8192];
                    int len;
                    while((len = in.read(buffer)) != -1){
                        gzipOut.write(buffer, 0, len);
                    }
                }

            }else{
                try (
                    InputStream in = process.getInputStream();
                    FileOutputStream fos = new FileOutputStream(outputFilePath);
                    )
                {
                    byte[] buffer = new byte[8192];
                    int len;
                    while((len = in.read(buffer)) != -1){
                        fos.write(buffer, 0, len);
                    }
                }
            }
            int exitCode = process.waitFor();
            if(exitCode == 0){
                System.out.println("Backup Successful: "+outputFilePath);
            }else{
                System.out.println("Backup failed. Exit Code : "+exitCode);
            }
        } catch (IOException | IllegalArgumentException e) {
            log.error(e.getMessage());
        }catch (InterruptedException e) {
            log.error("Process Interupted {}", e.getMessage());
            Thread.currentThread().interrupt();
        }

    }

}