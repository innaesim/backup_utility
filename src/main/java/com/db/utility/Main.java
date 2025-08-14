package com.db.utility;

import java.util.Scanner;

import com.db.utility.model.AppConfig;
import com.db.utility.service.Service;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Main {
    public static void main(String[] args){
        Scanner scanner = new Scanner(System.in);
        AppConfig config = new AppConfig();

        System.out.println("Enter Database type (postgresql/mysql): ");
        String dbType = scanner.nextLine().trim().toLowerCase();
        config.setType(dbType);

        System.out.println("Enter host [localhost]: ");
        String host = scanner.nextLine().trim();
        config.setHost(host.isEmpty() ? "localhost" : host);

        System.out.println("Enter port (leave blank for default): ");
        String portInput = scanner.nextLine().trim();
        if (portInput.isEmpty()) {
            config.setPort(dbType.equals("mysql") ? 3306 : 5432);
        } else {
            config.setPort(Integer.parseInt(portInput));
        }

        System.out.println("Enter database name: ");
        config.setName(scanner.nextLine().trim());

        System.out.println("Enter username: ");
        config.setUser(scanner.nextLine().trim());

        System.out.println("Enter password: ");
        config.setPassword(scanner.nextLine().trim());

        System.out.println("Enter output directory [./backups]: ");
        String outDir = scanner.nextLine().trim();
        config.setOutputDir(outDir.isEmpty() ? "./backups" : outDir);

        System.out.println("Compress backup? (y/n) [n]: ");
        String compressInput = scanner.nextLine().trim();
        config.setCompress(compressInput.equalsIgnoreCase("y"));

        scanner.close();
        
        try {
            Service service = new Service(config);
            service.runBackup();
        } catch (Exception e) {
            log.error("Backup Failed: {}", e.getMessage());
            Thread.currentThread().interrupt();
        }
    }
}