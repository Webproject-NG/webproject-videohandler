package de.gmasil.webproject.videohandler.exception;

import java.io.File;

public class FileNotFoundException extends RuntimeException {

    public FileNotFoundException(File file) {
        super("File does not exist: " + file.getAbsolutePath());
    }
}
