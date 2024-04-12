package de.gmasil.webproject.videohandler.service;

import de.gmasil.webproject.videohandler.exception.ExecutionException;
import de.gmasil.webproject.videohandler.exception.FileNotFoundException;
import de.gmasil.webproject.api.importexport.video.ImportSeekPreviewFile;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.PumpStreamHandler;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.Dimension;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.stream.Stream;

@Service
public class FileService {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().startsWith("windows");

    public FileService() {
        verifyCommands();
    }

    public void verifyCommands() {
        checkCommandExecutable("curl", "curl --version");
        checkCommandExecutable("ffmpeg", "ffmpeg -version");
        checkCommandExecutable("ffprobe", "ffprobe -version");
        checkCommandExecutable(IS_WINDOWS ? "magick convert" : "convert", (IS_WINDOWS ? "magick convert" : "convert") + " --version");
        checkCommandExecutable(IS_WINDOWS ? "magick identify" : "identify", (IS_WINDOWS ? "magick identify" : "identify") + " --version");
    }

    private boolean checkCommandExecutable(String name, String cmd) {
        try {
            exec(cmd, true);
        } catch (ExecutionException e) {
            LOG.warn("Command {} is not available, output: {}", name, e.getOutput());
            return false;
        }
        return true;
    }

    public float getVideoDuration(File file) {
        if (!file.exists()) {
            throw new FileNotFoundException(file);
        }
        String duration = exec("ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 " + file.getAbsolutePath()).replace("\r", "").replace("\n", "").trim();
        return Float.parseFloat(duration);
    }

    public String getVideoQuality(File file) {
        if (!file.exists()) {
            throw new FileNotFoundException(file);
        }
        String height = exec("ffprobe -v error -select_streams v:0 -show_entries stream=height -of csv=s=x:p=0 " + file.getAbsolutePath()).replace("\r", "").replace("\n", "").trim();
        return height + "p";
    }

    /**
     * Extracts a frame from a video at a given timestamp
     *
     * @param videoFile  video to extract frame from
     * @param time       timestamp to take frame
     * @param outputFile file to save the extracted frame to as JPG
     * @param quality    1 for best quality, 5 for worst
     * @param timeDigits amount of digits to cap the time after comma
     */
    public void extractFrameFromVideo(File videoFile, float time, File outputFile, int quality, int timeDigits) {
        if (!videoFile.exists()) {
            throw new FileNotFoundException(videoFile);
        }
        String timeString = String.format("%." + timeDigits + "f", time).replace(",", ".");
        exec("ffmpeg -y -accurate_seek -ss {TIME} -i {VIDEO} -frames:v 1 -qscale:v {QUALITY} {OUT}" //
                .replace("{VIDEO}", videoFile.getAbsolutePath()) //
                .replace("{TIME}", timeString) //
                .replace("{QUALITY}", "" + quality) //
                .replace("{OUT}", outputFile.getAbsolutePath()));
    }

    public void convertToMp4(File inputFile, File targetFile) {
        exec("ffmpeg -i {INPUT} -map 0 -c copy {TARGET}" //
                .replace("{INPUT}", inputFile.getAbsolutePath()) //
                .replace("{TARGET}", targetFile.getAbsolutePath()));
    }

    public void resizeImage(File imageFile, int height, File outputFile) {
        exec("{CMD} -resize 9999x{HEIGHT} {IMAGE} {OUT}" //
                .replace("{CMD}", IS_WINDOWS ? "magick convert" : "convert") //
                .replace("{HEIGHT}", "" + height) //
                .replace("{IMAGE}", imageFile.getAbsolutePath()) //
                .replace("{OUT}", outputFile.getAbsolutePath()));
    }

    public void mergeImages(File folder, File outputFile) {
        exec("{CMD} {FOLDER}/*.jpg -append {OUT}" //
                .replace("{CMD}", IS_WINDOWS ? "magick convert" : "convert") //
                .replace("{FOLDER}", folder.getAbsolutePath()) //
                .replace("{OUT}", outputFile.getAbsolutePath()));
    }

    public Dimension getImageDimensions(File imageFile) {
        String[] out = exec("{CMD} -format %wx%h {IMAGE}" //
                .replace("{CMD}", IS_WINDOWS ? "magick identify" : "identify") //
                .replace("{IMAGE}", imageFile.getAbsolutePath())) //
                .replace("\r", "").replace("\n", "").trim().split("x");
        return new Dimension(Integer.parseInt(out[0]), Integer.parseInt(out[1]));
    }

    public ImportSeekPreviewFile generateSeekPreviewFile(File videoFile, int frames, int previewHeight, int previewQuality) {
        // prepare
        File tmpFolder = new File(videoFile.getParentFile(), "tmp");
        try {
            FileUtils.deleteDirectory(tmpFolder);
        } catch (IOException e) {
            // nothing to do
        }
        File exportFolder = new File(tmpFolder, "original");
        File convertFolder = new File(tmpFolder, "compressed");
        exportFolder.mkdirs();
        convertFolder.mkdirs();
        // gather information
        float duration = getVideoDuration(videoFile);
        float step = duration / frames;
        final int timeDigits;
        if (duration < 120) {
            timeDigits = 0;
        } else if (duration < 180) {
            timeDigits = 1;
        } else {
            timeDigits = 3;
        }
        Stream.iterate(0L, x -> x + 1).limit(frames).parallel().forEach(i -> {
            String num = unify(i);
            extractFrameFromVideo(videoFile, step * i, new File(exportFolder, num + ".jpg"), previewQuality, timeDigits);
        });
        Stream.iterate(0L, x -> x + 1).limit(frames).parallel().forEach(i -> {
            String num = unify(i);
            resizeImage(new File(exportFolder, num + ".jpg"), previewHeight, new File(convertFolder, num + ".jpg"));
        });
        File mergedImage = new File(videoFile.getParentFile(), "videopreview.jpg");
        mergeImages(convertFolder, mergedImage);
        Dimension dim = getImageDimensions(new File(convertFolder, unify(0L) + ".jpg"));
        Dimension mergedDim = getImageDimensions(mergedImage);
        boolean error = false;
        if (mergedDim.width != dim.width) {
            LOG.warn("Image width of seek preview is incorrect");
            error = true;
        }
        if (mergedDim.height != dim.height * frames) {
            LOG.warn("Image height of seek preview is incorrect");
            error = true;
        }
        if (error) {
            return null;
        }
        try {
            FileUtils.deleteDirectory(tmpFolder);
        } catch (IOException e) {
            // nothing to do
        }
        return new ImportSeekPreviewFile(videoFile.getParentFile().getName() + "/videopreview.jpg", dim.width, dim.height, frames);
    }

    public String unify(long l) {
        return String.format("%1$5s", "" + l).replace(' ', '0');
    }

    public String exec(String command) {
        return exec(command, null, false);
    }

    public String exec(String command, boolean suppressErrors) {
        return exec(command, null, suppressErrors);
    }

    public String exec(String command, File workdir, boolean suppressErrors) {
        CommandLine commandline;
        if (IS_WINDOWS) {
            commandline = new CommandLine("cmd.exe");
            commandline.addArgument("/c");
            commandline.addArgument(command, false);
        } else {
            commandline = new CommandLine("/bin/bash");
            commandline.addArgument("-c");
            commandline.addArgument(command, false);
        }
        DefaultExecutor exec = new DefaultExecutor();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PumpStreamHandler streamHandler = new PumpStreamHandler(outputStream);
        exec.setStreamHandler(streamHandler);
        if (workdir != null) {
            exec.setWorkingDirectory(workdir);
        }
        try {
            exec.execute(commandline);
        } catch (Exception e) {
            if (!suppressErrors) {
                LOG.error("Error while executing command:\n{}", command);
                LOG.error("Output:\n{}", outputStream);
            }
            throw new ExecutionException(outputStream.toString());
        }
        return outputStream.toString();
    }
}
