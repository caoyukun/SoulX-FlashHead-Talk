package com.soulx.flashhead.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

@Slf4j
@Service
public class VideoMergeService {
    
    public File mergeVideos(List<String> videoPaths) throws IOException, InterruptedException {
        if (videoPaths == null || videoPaths.isEmpty()) {
            throw new IllegalArgumentException("没有视频片段可合并");
        }
        
        String timestamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
        String outputDir = "chat_results";
        Files.createDirectories(Paths.get(outputDir));
        
        String outputPath = outputDir + File.separator + "merged_" + timestamp + ".mp4";
        
        File listFile = createFileList(videoPaths);
        
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg",
                "-f", "concat",
                "-safe", "0",
                "-i", listFile.getAbsolutePath(),
                "-c", "copy",
                outputPath
            );
            
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("FFmpeg合并失败，退出码: " + exitCode);
            }
            
            log.info("视频合并成功: {}", outputPath);
            return new File(outputPath);
        } finally {
            if (listFile.exists()) {
                listFile.delete();
            }
        }
    }
    
    private File createFileList(List<String> videoPaths) throws IOException {
        File tempFile = File.createTempFile("video_list_", ".txt");
        
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(tempFile))) {
            for (String path : videoPaths) {
                String escapedPath = path.replace("'", "'\\''");
                writer.write("file '" + escapedPath + "'");
                writer.newLine();
            }
        }
        
        return tempFile;
    }
}
