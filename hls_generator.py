"""
HLS 生成器
使用 FFmpeg 将 FlashHead 生成的视频帧实时转封装为 HLS 格式
"""
import os
import sys
import subprocess
import threading
import queue
import time
import requests
import numpy as np
import tempfile
import shutil
from datetime import datetime
from loguru import logger


class HlsGenerator:
    """
    HLS 流生成器
    将视频帧和音频实时编码为 HLS 格式，并通过 HTTP 推送 TS 片段
    """

    def __init__(self, backend_url="http://localhost:8080", video_resolution=(512, 512), fps=25):
        self.backend_url = backend_url
        self.video_resolution = video_resolution
        self.fps = fps
        self.ffmpeg_process = None
        self.audio_temp_dir = None
        self.segment_queue = queue.Queue()
        self.is_running = False
        self.session_id = None
        self.segment_thread = None
        self.sequence_number = 0

    def start(self, session_id, audio_path=None):
        """
        启动 HLS 生成

        Args:
            session_id: HLS 会话 ID
            audio_path: 音频文件路径（可选）
        """
        self.session_id = session_id
        self.is_running = True
        self.sequence_number = 0

        # 创建临时目录存放音频文件
        self.audio_temp_dir = tempfile.mkdtemp()

        # 创建命名管道用于音频输入（如果已存在则删除）
        audio_pipe_path = os.path.join(self.audio_temp_dir, "audio_pipe")
        if os.path.exists(audio_pipe_path):
            os.remove(audio_pipe_path)
        os.mkfifo(audio_pipe_path)

        # 构建 FFmpeg 命令
        # 方案：使用文件输出模式，然后通过回调上传
        hls_output_dir = os.path.join(self.audio_temp_dir, "hls_output")
        os.makedirs(hls_output_dir, exist_ok=True)

        width, height = self.video_resolution

        cmd = [
            "ffmpeg",
            "-y",
            "-f", "rawvideo",
            "-pix_fmt", "rgb24",
            "-s", f"{width}x{height}",
            "-r", str(self.fps),
            "-i", "-",  # 视频从 stdin 输入
            "-f", "s16le",
            "-ar", "16000",
            "-ac", "1",
            "-i", audio_pipe_path,  # 音频从命名管道输入
            "-c:v", "libx264",
            "-pix_fmt", "yuv420p",
            "-profile:v", "baseline",
            "-level", "3.0",
            "-preset", "ultrafast",
            "-tune", "zerolatency",
            "-c:a", "aac",
            "-b:a", "128k",
            "-f", "hls",
            "-hls_time", "2",  # 每个片段 2 秒
            "-hls_list_size", "0",  # 保留所有片段
            "-hls_segment_type", "mpegts",
            "-hls_segment_filename", os.path.join(hls_output_dir, "segment_%05d.ts"),
            "-hls_flags", "+omit_endlist",  # 直播模式，不添加 ENDLIST
            os.path.join(hls_output_dir, "playlist.m3u8")
        ]

        logger.info(f"启动 FFmpeg HLS 生成: session_id={session_id}")
        logger.debug(f"FFmpeg 命令: {' '.join(cmd)}")

        # 启动 FFmpeg 进程
        self.ffmpeg_process = subprocess.Popen(
            cmd,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            bufsize=0
        )

        # 启动音频写入线程
        if audio_path and os.path.exists(audio_path):
            audio_thread = threading.Thread(
                target=self._write_audio_to_pipe,
                args=(audio_path, audio_pipe_path)
            )
            audio_thread.daemon = True
            audio_thread.start()

        # 启动片段监控和上传线程
        self.segment_thread = threading.Thread(target=self._monitor_and_upload_segments, args=(hls_output_dir,))
        self.segment_thread.daemon = True
        self.segment_thread.start()

        return self.ffmpeg_process.stdin

    def _write_audio_to_pipe(self, audio_path, pipe_path):
        """将音频文件写入命名管道"""
        try:
            # 转换音频为 16kHz 单声道 s16le 格式
            cmd = [
                "ffmpeg", "-y",
                "-i", audio_path,
                "-f", "s16le",
                "-ar", "16000",
                "-ac", "1",
                "-"
            ]

            with subprocess.Popen(cmd, stdout=subprocess.PIPE) as proc:
                with open(pipe_path, "wb") as pipe:
                    while True:
                        data = proc.stdout.read(4096)
                        if not data:
                            break
                        pipe.write(data)
                        pipe.flush()

            logger.info(f"音频写入完成: {audio_path}")
        except Exception as e:
            logger.error(f"音频写入失败: {e}")

    def _monitor_and_upload_segments(self, hls_output_dir):
        """监控 HLS 输出目录，上传新生成的 TS 片段"""
        uploaded_segments = set()

        while self.is_running:
            try:
                # 扫描目录中的 TS 片段
                for filename in os.listdir(hls_output_dir):
                    if filename.endswith(".ts") and filename not in uploaded_segments:
                        segment_path = os.path.join(hls_output_dir, filename)

                        # 等待文件写入完成
                        time.sleep(0.1)

                        # 上传片段
                        self._upload_segment(segment_path, filename)
                        uploaded_segments.add(filename)

                time.sleep(0.5)
            except Exception as e:
                logger.error(f"监控片段失败: {e}")
                time.sleep(1)

    def _upload_segment(self, segment_path, filename):
        """上传 TS 片段到后端"""
        try:
            # 解析序列号
            sequence = int(filename.replace("segment_", "").replace(".ts", ""))

            # 读取片段数据
            with open(segment_path, "rb") as f:
                data = f.read()

            # 计算时长（假设每个片段 2 秒）
            duration = 2.0

            # 上传到后端
            upload_url = f"{self.backend_url}/api/hls/{self.session_id}/segment"

            response = requests.post(
                upload_url,
                params={"sequence": sequence, "duration": duration},
                data=data,
                headers={"Content-Type": "application/octet-stream"},
                timeout=10
            )

            if response.status_code == 200:
                logger.debug(f"上传 TS 片段成功: {filename}, size={len(data)} bytes")
            else:
                logger.warning(f"上传 TS 片段失败: {filename}, status={response.status_code}")

        except Exception as e:
            logger.error(f"上传 TS 片段失败: {filename}, error={e}")

    def write_video_frame(self, frame_data):
        """
        写入视频帧数据

        Args:
            frame_data: numpy 数组，形状为 (H, W, 3)，RGB 格式
        """
        if self.ffmpeg_process and self.ffmpeg_process.stdin:
            try:
                # 确保数据格式正确
                if isinstance(frame_data, np.ndarray):
                    # 转换为 uint8 类型
                    if frame_data.dtype != np.uint8:
                        frame_data = frame_data.astype(np.uint8)

                    # 写入帧数据
                    self.ffmpeg_process.stdin.write(frame_data.tobytes())
                    self.ffmpeg_process.stdin.flush()
            except Exception as e:
                logger.error(f"写入视频帧失败: {e}")

    def stop(self):
        """停止 HLS 生成"""
        logger.info(f"停止 HLS 生成: session_id={self.session_id}")

        self.is_running = False

        # 关闭 FFmpeg stdin
        if self.ffmpeg_process and self.ffmpeg_process.stdin:
            try:
                self.ffmpeg_process.stdin.close()
            except:
                pass

        # 等待 FFmpeg 进程结束
        if self.ffmpeg_process:
            try:
                self.ffmpeg_process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.ffmpeg_process.terminate()
                try:
                    self.ffmpeg_process.wait(timeout=2)
                except:
                    self.ffmpeg_process.kill()

        # 等待上传线程结束
        if self.segment_thread:
            self.segment_thread.join(timeout=5)

        # 通知后端流结束
        try:
            end_url = f"{self.backend_url}/api/hls/{self.session_id}/end"
            requests.post(end_url, timeout=5)
        except Exception as e:
            logger.warning(f"通知后端流结束失败: {e}")

        # 清理临时目录
        if self.audio_temp_dir and os.path.exists(self.audio_temp_dir):
            try:
                shutil.rmtree(self.audio_temp_dir)
            except Exception as e:
                logger.warning(f"清理临时目录失败: {e}")

        logger.info(f"HLS 生成已停止: session_id={self.session_id}")


class HlsGeneratorDirectUpload:
    """
    HLS 生成器 - 直接上传版本
    使用 FFmpeg 输出到内存管道，然后直接上传到后端
    """

    def __init__(self, backend_url="http://localhost:8080", video_resolution=(512, 512), fps=25):
        self.backend_url = backend_url
        self.video_resolution = video_resolution
        self.fps = fps
        self.ffmpeg_process = None
        self.is_running = False
        self.session_id = None
        self.upload_thread = None
        self.segment_parser = None

    def start(self, session_id, audio_path=None):
        """
        启动 HLS 生成

        Args:
            session_id: HLS 会话 ID
            audio_path: 音频文件路径（可选）
        """
        self.session_id = session_id
        self.is_running = True

        # 创建临时目录
        temp_dir = tempfile.mkdtemp()
        audio_pipe = os.path.join(temp_dir, "audio_pipe")
        video_pipe = os.path.join(temp_dir, "video_pipe")

        os.mkfifo(audio_pipe)
        os.mkfifo(video_pipe)

        width, height = self.video_resolution

        # 构建 FFmpeg 命令 - 使用 segment 格式直接输出到管道
        # 这个方案更复杂，需要使用自定义的 segment 处理
        # 暂时使用文件方案

        return self._start_file_based(session_id, audio_path, temp_dir)

    def _start_file_based(self, session_id, audio_path, temp_dir):
        """基于文件的 HLS 生成方案"""
        self.temp_dir = temp_dir  # 保存 temp_dir 供后续使用
        hls_dir = os.path.join(temp_dir, "hls")
        os.makedirs(hls_dir, exist_ok=True)

        width, height = self.video_resolution

        # 创建音频管道（如果已存在则删除）
        audio_pipe = os.path.join(temp_dir, "audio_pipe")
        if os.path.exists(audio_pipe):
            os.remove(audio_pipe)
        os.mkfifo(audio_pipe)

        cmd = [
            "ffmpeg",
            "-y",
            "-f", "rawvideo",
            "-pix_fmt", "rgb24",
            "-s", f"{width}x{height}",
            "-r", str(self.fps),
            "-thread_queue_size", "512",
            "-i", "-",
            "-f", "s16le",
            "-ar", "16000",
            "-ac", "1",
            "-thread_queue_size", "512",
            "-i", audio_pipe,
            "-c:v", "libx264",
            "-pix_fmt", "yuv420p",
            "-profile:v", "baseline",
            "-level", "3.0",
            "-preset", "ultrafast",
            "-tune", "zerolatency",
            "-crf", "23",
            "-c:a", "aac",
            "-b:a", "128k",
            "-f", "hls",
            "-hls_time", "2",
            "-hls_list_size", "0",
            "-hls_segment_type", "mpegts",
            "-hls_segment_filename", os.path.join(hls_dir, "segment_%05d.ts"),
            "-hls_flags", "+omit_endlist",
            os.path.join(hls_dir, "playlist.m3u8")
        ]

        logger.info(f"启动 FFmpeg HLS 生成: session_id={session_id}")
        logger.debug(f"FFmpeg 命令: {' '.join(cmd)}")

        self.ffmpeg_process = subprocess.Popen(
            cmd,
            stdin=subprocess.PIPE,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            bufsize=10*1024*1024  # 10MB 缓冲区
        )
        
        # 启动 FFmpeg stderr 监控线程
        def monitor_ffmpeg_stderr():
            """监控 FFmpeg 的 stderr 输出"""
            try:
                while self.is_running and self.ffmpeg_process:
                    line = self.ffmpeg_process.stderr.readline()
                    if not line:
                        break
                    line_str = line.decode('utf-8', errors='ignore').strip()
                    if line_str:
                        # 只记录错误和警告
                        if 'error' in line_str.lower() or 'warning' in line_str.lower():
                            logger.warning(f"FFmpeg: {line_str}")
                        else:
                            logger.debug(f"FFmpeg: {line_str}")
            except Exception as e:
                logger.error(f"FFmpeg 监控线程异常: {e}")
        
        stderr_thread = threading.Thread(target=monitor_ffmpeg_stderr)
        stderr_thread.daemon = True
        stderr_thread.start()

        # 启动音频写入线程
        if audio_path and os.path.exists(audio_path):
            audio_thread = threading.Thread(
                target=self._write_audio,
                args=(audio_path, audio_pipe)
            )
            audio_thread.daemon = True
            audio_thread.start()

        # 启动片段上传线程
        self.upload_thread = threading.Thread(
            target=self._upload_worker,
            args=(hls_dir,)
        )
        self.upload_thread.daemon = True
        self.upload_thread.start()

        return self.ffmpeg_process.stdin

    def _write_audio(self, audio_path, audio_pipe):
        """写入音频到管道"""
        try:
            # 转换音频格式
            cmd = [
                "ffmpeg", "-y",
                "-i", audio_path,
                "-f", "s16le",
                "-ar", "16000",
                "-ac", "1",
                "-"
            ]

            with subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL) as proc:
                with open(audio_pipe, "wb") as pipe:
                    while True:
                        chunk = proc.stdout.read(8192)
                        if not chunk:
                            break
                        pipe.write(chunk)
                        pipe.flush()

            logger.info("音频写入完成")
        except Exception as e:
            logger.error(f"音频写入失败: {e}")

    def _upload_worker(self, hls_dir):
        """上传工作线程"""
        uploaded = set()
        last_m3u8_content = ""
        
        logger.info(f"上传工作线程启动: session_id={self.session_id}, hls_dir={hls_dir}")

        while self.is_running:
            try:
                # 检查目录是否存在
                if not os.path.exists(hls_dir):
                    logger.warning(f"HLS 目录不存在: {hls_dir}")
                    time.sleep(1)
                    continue
                
                # 检查并上传新的 TS 片段
                files = os.listdir(hls_dir)
                ts_files = [f for f in files if f.endswith(".ts")]
                
                if ts_files:
                    logger.debug(f"发现 {len(ts_files)} 个 TS 文件，已上传 {len(uploaded)} 个")
                
                for fname in ts_files:
                    if fname not in uploaded:
                        fpath = os.path.join(hls_dir, fname)

                        # 等待文件稳定
                        time.sleep(0.2)
                        
                        # 检查文件大小
                        file_size = os.path.getsize(fpath)
                        if file_size == 0:
                            logger.warning(f"TS 文件为空，跳过: {fname}")
                            continue

                        try:
                            with open(fpath, "rb") as f:
                                data = f.read()

                            seq = int(fname.replace("segment_", "").replace(".ts", ""))
                            duration = 2.0

                            url = f"{self.backend_url}/api/hls/{self.session_id}/segment"
                            logger.debug(f"上传片段: {fname}, seq={seq}, size={len(data)} bytes")
                            
                            resp = requests.post(
                                url,
                                params={"sequence": seq, "duration": duration},
                                data=data,
                                headers={"Content-Type": "application/octet-stream"},
                                timeout=10
                            )

                            if resp.status_code == 200:
                                uploaded.add(fname)
                                logger.info(f"上传片段成功: {fname}, seq={seq}")
                            else:
                                logger.warning(f"上传片段失败: {fname}, status={resp.status_code}, response={resp.text}")
                        except Exception as e:
                            logger.error(f"上传片段异常: {fname}, {e}")

                time.sleep(0.5)
            except Exception as e:
                logger.error(f"上传工作线程异常: {e}")
                time.sleep(1)
        
        logger.info(f"上传工作线程结束: session_id={self.session_id}")

    def write_video_frame(self, frame_data):
        """写入视频帧"""
        if self.ffmpeg_process and self.ffmpeg_process.stdin:
            try:
                if isinstance(frame_data, np.ndarray):
                    if frame_data.dtype != np.uint8:
                        frame_data = frame_data.astype(np.uint8)
                    self.ffmpeg_process.stdin.write(frame_data.tobytes())
                    # 定期 flush 以确保数据及时传递给 FFmpeg
                    # 注意：每帧都 flush 可能影响性能，但这里需要实时性
                    self.ffmpeg_process.stdin.flush()
            except Exception as e:
                logger.error(f"写入帧失败: {e}")

    def stop(self):
        """停止生成"""
        self.is_running = False

        if self.ffmpeg_process:
            try:
                self.ffmpeg_process.stdin.close()
            except:
                pass

            try:
                self.ffmpeg_process.wait(timeout=5)
            except:
                self.ffmpeg_process.terminate()
                try:
                    self.ffmpeg_process.wait(timeout=2)
                except:
                    self.ffmpeg_process.kill()

        if self.upload_thread:
            self.upload_thread.join(timeout=5)

        try:
            requests.post(
                f"{self.backend_url}/api/hls/{self.session_id}/end",
                timeout=5
            )
        except:
            pass

        logger.info(f"HLS 生成停止: {self.session_id}")


# 保持向后兼容的别名
HlsGenerator = HlsGeneratorDirectUpload
