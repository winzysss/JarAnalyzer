package com.jaranalyzer;

import javax.swing.*;
import java.io.File;

class DirPreferences {
    private AppPreferences appPrefs;

    public DirPreferences(AppPreferences appPrefs) {
        this.appPrefs = appPrefs;
    }

    void retrieveOpenDialogDir(JFileChooser fc) {
        try {
            String currentDirStr = appPrefs.getFileOpenCurrentDirectory();
            if (currentDirStr != null && currentDirStr.trim().length() > 0) {
                File currentDir = new File(currentDirStr);
                if (currentDir.exists() && currentDir.isDirectory()) {
                    fc.setCurrentDirectory(currentDir);
                }
            }
        } catch (Exception e) {
            JarAnalyzer.showExceptionDialog(LanguageManager.getString("dialog.exception"), e);
        }
    }

    void saveOpenDialogDir(JFileChooser fc) {
        try {
            File currentDir = fc.getCurrentDirectory();
            if (currentDir != null && currentDir.exists() && currentDir.isDirectory()) {
                appPrefs.setFileOpenCurrentDirectory(currentDir.getAbsolutePath());
            }
        } catch (Exception e) {
            JarAnalyzer.showExceptionDialog(LanguageManager.getString("dialog.exception"), e);
        }
    }

    void retrieveSaveDialogDir(JFileChooser fc) {
        try {
            String currentDirStr = appPrefs.getFileSaveCurrentDirectory();
            if (currentDirStr != null && currentDirStr.trim().length() > 0) {
                File currentDir = new File(currentDirStr);
                if (currentDir.exists() && currentDir.isDirectory()) {
                    fc.setCurrentDirectory(currentDir);
                }
            }
        } catch (Exception e) {
            JarAnalyzer.showExceptionDialog(LanguageManager.getString("dialog.exception"), e);
        }
    }

    void saveSaveDialogDir(JFileChooser fc) {
        try {
            File currentDir = fc.getCurrentDirectory();
            if (currentDir != null && currentDir.exists() && currentDir.isDirectory()) {
                appPrefs.setFileSaveCurrentDirectory(currentDir.getAbsolutePath());
            }
        } catch (Exception e) {
            JarAnalyzer.showExceptionDialog(LanguageManager.getString("dialog.exception"), e);
        }
    }
}
