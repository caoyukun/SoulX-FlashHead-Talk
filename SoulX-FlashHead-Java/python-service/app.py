"""
FlashHead Python 模型服务
提供 REST API 供 Java 后端调用
支持真正的流式视频生成
"""
import os
import sys
import tempfile
import threading
import queue
import time
from datetime import datetime
from collections import deque

from flask import Flask, request, jsonify, send_file
from flask_cors import CORS
from loguru import logger
import requests

# 确保项目根目录在sys.path中，这样可以找到flash_head模块
# 脚本从项目根目录启动，所以使用当前工作目录
project_root = os.getcwd()
sys.path.insert(0, project_root)
logger.info(f"Project root: {project_root}")
logger.info(f"sys.path: {sys.path}")

try:
    from flash_head.inference import (
        get_pipeline,
        get_base_data,
        get_infer_params,
        get_audio_embedding,
        run_pipeline,
    )
    FLASHHEAD_AVAILABLE = True
except ImportError:
    logger.warning("FlashHead 模块未找到，部分功能将不可用")
    FLASHHEAD_AVAILABLE = False

app = Flask(__name__)
CORS(app)

CHUNKS_PER_SEGMENT = 1

pipeline = None
loaded_ckpt_dir = None
loaded_wav2vec_dir = None
loaded_model_type = None

# 全局回调URL，用于通知Java后端新视频段
callback_url = None

# ==================== 视频生成任务队列 ====================
class VideoGenerationTask:
    """视频生成任务"""
    def __init__(self, task_type, params, future_result=None):
        self.task_type = task_type  # 'hls_video' 或 'hls_idle'
        self.params = params
        self.future_result = future_result  # 用于异步返回结果
        self.status = 'pending'  # pending, running, completed, failed
        self.error = None

class TaskQueue:
    """串行任务队列"""
    def __init__(self):
        self.queue = queue.Queue()
        self.lock = threading.Lock()
        self.current_task = None
        self.worker_thread = None
        self.running = False
        self._results = {}  # task_id -> result
        
    def start(self):
        """启动队列处理器"""
        if not self.running:
            self.running = True
            self.worker_thread = threading.Thread(target=self._process_queue, daemon=True)
            self.worker_thread.start()
            logger.info("视频生成任务队列已启动")
    
    def stop(self):
        """停止队列处理器"""
        self.running = False
        if self.worker_thread:
            self.worker_thread.join(timeout=5)
    
    def submit(self, task):
        """提交任务到队列"""
        task_id = id(task)
        self._results[task_id] = {'status': 'pending', 'result': None, 'error': None}
        self.queue.put(task)
        logger.info(f"任务已提交到队列: type={task.task_type}, queue_size={self.queue.qsize()}")
        return task_id
    
    def get_result(self, task_id, timeout=None):
        """获取任务结果（阻塞）"""
        start_time = time.time()
        while timeout is None or (time.time() - start_time) < timeout:
            with self.lock:
                if task_id in self._results:
                    result = self._results[task_id]
                    if result['status'] in ['completed', 'failed']:
                        return result
            time.sleep(0.1)
        return None
    
    def _process_queue(self):
        """队列处理主循环"""
        while self.running:
            try:
                # 获取下一个任务（阻塞等待）
                task = self.queue.get(timeout=1)
                
                with self.lock:
                    self.current_task = task
                    task.status = 'running'
                    task_id = id(task)
                    self._results[task_id]['status'] = 'running'
                
                logger.info(f"开始执行任务: type={task.task_type}")
                
                try:
                    # 执行任务
                    if task.task_type == 'hls_video':
                        result = self._execute_hls_video_task(task.params)
                    elif task.task_type == 'hls_idle':
                        result = self._execute_hls_idle_task(task.params)
                    else:
                        raise ValueError(f"未知任务类型: {task.task_type}")
                    
                    with self.lock:
                        self._results[task_id]['status'] = 'completed'
                        self._results[task_id]['result'] = result
                    task.status = 'completed'
                    logger.info(f"任务执行完成: type={task.task_type}")
                    
                except Exception as e:
                    logger.error(f"任务执行失败: type={task.task_type}, error={e}")
                    with self.lock:
                        self._results[task_id]['status'] = 'failed'
                        self._results[task_id]['error'] = str(e)
                    task.status = 'failed'
                    task.error = str(e)
                
                with self.lock:
                    self.current_task = None
                
                self.queue.task_done()
                
            except queue.Empty:
                continue
            except Exception as e:
                logger.error(f"队列处理异常: {e}")
    
    def _execute_hls_video_task(self, params):
        """执行 HLS 视频生成任务"""
        return _generate_hls_video_internal(**params)
    
    def _execute_hls_idle_task(self, params):
        """执行 HLS 空闲视频生成任务"""
        return _generate_hls_idle_internal(**params)

# 全局任务队列实例
task_queue = TaskQueue()

# 启动队列
task_queue.start()


def _write_frames_to_mp4(frames_list, video_path, fps):
    """将视频帧列表写入MP4文件"""
    import numpy as np
    import imageio
    import torch
    
    os.makedirs(os.path.dirname(video_path) or ".", exist_ok=True)
    with imageio.get_writer(
        video_path,
        format="mp4",
        mode="I",
        fps=fps,
        codec="h264",
        ffmpeg_params=["-bf", "0"],
    ) as writer:
        for frames in frames_list:
            if isinstance(frames, torch.Tensor):
                frames_np = frames.numpy().astype(np.uint8)
            else:
                frames_np = frames.astype(np.uint8)
            for i in range(frames_np.shape[0]):
                writer.append_data(frames_np[i, :, :, :])
    return video_path


def _generate_hls_video_internal(audio_path, cond_image, ckpt_dir, wav2vec_dir, model_type, 
                                  seed, use_face_crop, stream_id, backend_url, start_sequence):
    """内部函数：生成 HLS 视频（在队列中串行执行）"""
    import numpy as np
    import torch
    import librosa
    from collections import deque
    from hls_generator import HlsGenerator
    
    logger.info(f"[队列任务] 开始生成 HLS 视频: stream_id={stream_id}")
    
    global pipeline, loaded_ckpt_dir, loaded_wav2vec_dir, loaded_model_type
    
    # 加载模型（如果需要）
    if (
        pipeline is None
        or loaded_ckpt_dir != ckpt_dir
        or loaded_wav2vec_dir != wav2vec_dir
        or loaded_model_type != model_type
    ):
        logger.info(f"[队列任务] 加载模型: ckpt_dir={ckpt_dir}")
        pipeline = get_pipeline(
            world_size=1,
            ckpt_dir=ckpt_dir,
            model_type=model_type,
            wav2vec_dir=wav2vec_dir,
        )
        loaded_ckpt_dir = ckpt_dir
        loaded_wav2vec_dir = wav2vec_dir
        loaded_model_type = model_type
    
    base_seed = int(seed) if seed >= 0 else 9999
    get_base_data(
        pipeline,
        cond_image_path_or_dir=cond_image,
        base_seed=base_seed,
        use_face_crop=use_face_crop,
    )
    
    infer_params = get_infer_params()
    sample_rate = infer_params["sample_rate"]
    tgt_fps = infer_params["tgt_fps"]
    cached_audio_duration = infer_params["cached_audio_duration"]
    frame_num = infer_params["frame_num"]
    motion_frames_num = infer_params["motion_frames_num"]
    slice_len = frame_num - motion_frames_num
    
    human_speech_array_all, _ = librosa.load(audio_path, sr=sample_rate, mono=True)
    human_speech_array_slice_len = slice_len * sample_rate // tgt_fps
    
    remainder = len(human_speech_array_all) % human_speech_array_slice_len
    if remainder > 0:
        pad_length = human_speech_array_slice_len - remainder
        human_speech_array_all = np.concatenate(
            [human_speech_array_all, np.zeros(pad_length, dtype=human_speech_array_all.dtype)]
        )
    human_speech_array_slices = human_speech_array_all.reshape(-1, human_speech_array_slice_len)
    total_chunks = len(human_speech_array_slices)
    
    if total_chunks == 0:
        raise ValueError("音频太短")
    
    # 创建 HLS 生成器
    hls_gen = HlsGenerator(backend_url=backend_url, video_resolution=(512, 512), fps=tgt_fps)
    
    # 启动 HLS 生成
    video_input = hls_gen.start(session_id=stream_id, audio_path=audio_path, start_sequence=start_sequence)
    
    try:
        logger.info(f"[队列任务] 开始生成视频帧: total_chunks={total_chunks}")
        
        audio_dq = deque([0.0] * (sample_rate * cached_audio_duration), 
                        maxlen=sample_rate * cached_audio_duration)
        cached_audio_length_sum = sample_rate * cached_audio_duration
        audio_end_idx = cached_audio_duration * tgt_fps
        audio_start_idx = audio_end_idx - frame_num
        
        total_frames = 0
        
        for chunk_idx, human_speech_array in enumerate(human_speech_array_slices):
            logger.info(f"[队列任务] 处理 chunk {chunk_idx + 1}/{total_chunks}")
            
            audio_dq.extend(human_speech_array.tolist())
            audio_array = np.array(audio_dq)
            audio_embedding = get_audio_embedding(pipeline, audio_array, audio_start_idx, audio_end_idx)
            torch.cuda.synchronize()
            start_time = time.time()
            video = run_pipeline(pipeline, audio_embedding)
            video = video[motion_frames_num:]
            torch.cuda.synchronize()
            
            chunk_frame_count = video.shape[0]
            logger.info(f"[队列任务] HLS chunk-{chunk_idx} 推理完成, 耗时: {time.time() - start_time:.2f}s, frames={chunk_frame_count}")
            
            # 将帧数据写入 HLS 生成器
            chunk_frames_np = video.cpu().numpy()
            for i in range(chunk_frames_np.shape[0]):
                frame = chunk_frames_np[i]
                hls_gen.write_video_frame(frame)
                total_frames += 1
        
        logger.info(f"[队列任务] 所有视频帧生成完成，总共 {total_frames} 帧, 预期片段数: {total_chunks}")
        
        # 关闭视频输入
        if video_input:
            video_input.close()
        
        # 通知后端视频生成完成，告知预期的片段数量
        logger.info(f"[队列任务] 通知后端视频生成完成，预期片段数: {total_chunks}, 起始序号: {start_sequence}")
        try:
            complete_url = f"{backend_url}/api/callback/generation-complete"
            response = requests.post(
                complete_url,
                json={
                    "stream_id": stream_id,
                    "segment_count": total_chunks,
                    "start_sequence": start_sequence,
                    "total_frames": total_frames
                },
                timeout=10
            )
            logger.info(f"[队列任务] 通知后端视频生成完成, 状态: {response.status_code}, 预期片段数: {total_chunks}")
        except Exception as e:
            logger.warning(f"[队列任务] 通知后端生成完成失败: {e}")
    
    finally:
        pass  # 不调用 hls_gen.stop()，保持会话持续
    
    hls_url = f"{backend_url}/api/hls/{stream_id}/playlist.m3u8"
    logger.info(f"[队列任务] HLS 视频生成完成: {hls_url}")
    
    return {"stream_id": stream_id, "hls_url": hls_url}


def _generate_hls_idle_internal(duration, cond_image, ckpt_dir, wav2vec_dir, model_type,
                                 seed, use_face_crop, stream_id, backend_url, start_sequence):
    """内部函数：生成 HLS 空闲视频（在队列中串行执行）"""
    import numpy as np
    import torch
    import tempfile
    import shutil
    from collections import deque
    from hls_generator import HlsGenerator
    
    logger.info(f"[队列任务] 开始生成 HLS 空闲视频: stream_id={stream_id}, duration={duration}")
    
    global pipeline, loaded_ckpt_dir, loaded_wav2vec_dir, loaded_model_type
    
    # 加载模型（如果需要）
    if (
        pipeline is None
        or loaded_ckpt_dir != ckpt_dir
        or loaded_wav2vec_dir != wav2vec_dir
        or loaded_model_type != model_type
    ):
        logger.info(f"[队列任务] 加载模型: ckpt_dir={ckpt_dir}")
        pipeline = get_pipeline(
            world_size=1,
            ckpt_dir=ckpt_dir,
            model_type=model_type,
            wav2vec_dir=wav2vec_dir,
        )
        loaded_ckpt_dir = ckpt_dir
        loaded_wav2vec_dir = wav2vec_dir
        loaded_model_type = model_type
    
    base_seed = int(seed) if seed >= 0 else 9999
    get_base_data(
        pipeline,
        cond_image_path_or_dir=cond_image,
        base_seed=base_seed,
        use_face_crop=use_face_crop,
    )
    
    infer_params = get_infer_params()
    sample_rate = infer_params["sample_rate"]
    tgt_fps = infer_params["tgt_fps"]
    cached_audio_duration = infer_params["cached_audio_duration"]
    frame_num = infer_params["frame_num"]
    motion_frames_num = infer_params["motion_frames_num"]
    slice_len = frame_num - motion_frames_num
    
    # 创建静音音频
    silent_audio = create_silent_audio(duration=duration, sample_rate=sample_rate)
    human_speech_array_slice_len = slice_len * sample_rate // tgt_fps
    remainder = len(silent_audio) % human_speech_array_slice_len
    if remainder > 0:
        pad_length = human_speech_array_slice_len - remainder
        silent_audio = np.concatenate(
            [silent_audio, np.zeros(pad_length, dtype=silent_audio.dtype)]
        )
    human_speech_array_slices = silent_audio.reshape(-1, human_speech_array_slice_len)
    total_chunks = len(human_speech_array_slices)
    
    if total_chunks == 0:
        raise ValueError("音频太短")
    
    logger.info(f"[队列任务] 空闲视频预期片段数: {total_chunks}, 起始序号: {start_sequence}")
    
    # 在 session_id 目录下创建音频文件
    session_temp_dir = os.path.join("/home/yukun/SoulX-FlashHead/chat_results", stream_id, "audio")
    os.makedirs(session_temp_dir, exist_ok=True)
    audio_path = os.path.join(session_temp_dir, "silent.wav")
    _save_chunk_audio_to_wav(silent_audio, audio_path, sample_rate)
    
    # 创建 HLS 生成器
    hls_gen = HlsGenerator(backend_url=backend_url, video_resolution=(512, 512), fps=tgt_fps)
    
    # 启动 HLS 生成
    video_input = hls_gen.start(session_id=stream_id, audio_path=audio_path, start_sequence=start_sequence)
    
    try:
        cached_audio_length_sum = sample_rate * cached_audio_duration
        audio_end_idx = cached_audio_duration * tgt_fps
        audio_start_idx = audio_end_idx - frame_num
        
        audio_dq = deque([0.0] * cached_audio_length_sum, maxlen=cached_audio_length_sum)
        
        for chunk_idx, human_speech_array in enumerate(human_speech_array_slices):
            audio_dq.extend(human_speech_array.tolist())
            audio_array = np.array(audio_dq)
            audio_embedding = get_audio_embedding(pipeline, audio_array, audio_start_idx, audio_end_idx)
            torch.cuda.synchronize()
            start_time = time.time()
            video = run_pipeline(pipeline, audio_embedding)
            video = video[motion_frames_num:]
            torch.cuda.synchronize()
            logger.info(f"[队列任务] HLS 空闲视频 chunk-{chunk_idx} 推理完成, 耗时: {time.time() - start_time:.2f}s")
            
            # 将帧数据写入 HLS 生成器
            chunk_frames_np = video.cpu().numpy()
            for i in range(chunk_frames_np.shape[0]):
                frame = chunk_frames_np[i]
                hls_gen.write_video_frame(frame)
        
        logger.info(f"[队列任务] 空闲视频所有帧生成完成，预期片段数: {total_chunks}")
        
        # 关闭视频输入
        if video_input:
            video_input.close()
        
        # 通知后端视频生成完成，告知预期的片段数量
        logger.info(f"[队列任务] 通知后端空闲视频生成完成，预期片段数: {total_chunks}, 起始序号: {start_sequence}")
        try:
            complete_url = f"{backend_url}/api/callback/generation-complete"
            response = requests.post(
                complete_url,
                json={
                    "stream_id": stream_id,
                    "segment_count": total_chunks,
                    "start_sequence": start_sequence,
                    "video_type": "idle"
                },
                timeout=10
            )
            logger.info(f"[队列任务] 通知后端空闲视频生成完成, 状态: {response.status_code}, 预期片段数: {total_chunks}")
        except Exception as e:
            logger.warning(f"[队列任务] 通知后端空闲视频生成完成失败: {e}")
        
    finally:
        # 不调用 hls_gen.stop()，保持会话持续
        # 不清理 session 目录下的文件，保持文件
        pass
    
    hls_url = f"{backend_url}/api/hls/{stream_id}/playlist.m3u8"
    logger.info(f"[队列任务] HLS 空闲视频生成完成: {hls_url}")
    
    return {"stream_id": stream_id, "hls_url": hls_url}


def save_video_with_audio(frames_list, video_path, audio_path, fps):
    """将视频帧和音频合并为完整的MP4文件，使用适合流式播放的格式"""
    import subprocess
    
    temp_path = video_path.replace(".mp4", "_temp.mp4")
    _write_frames_to_mp4(frames_list, temp_path, fps)
    try:
        cmd = [
            "ffmpeg", "-y",
            "-i", temp_path,
            "-i", audio_path,
            "-c:v", "libx264",
            "-pix_fmt", "yuv420p",
            "-profile:v", "baseline",
            "-level", "3.0",
            "-movflags", "frag_keyframe+empty_moov+default_base_moof",
            "-c:a", "aac",
            "-b:a", "128k",
            "-f", "mp4",
            video_path,
        ]
        subprocess.run(cmd, check=True, capture_output=True)
    finally:
        if os.path.exists(temp_path):
            os.remove(temp_path)
    return video_path


def _save_chunk_audio_to_wav(audio_array, wav_path, sample_rate=16000):
    """将音频数组保存为WAV文件"""
    import numpy as np
    import wave
    
    os.makedirs(os.path.dirname(wav_path) or ".", exist_ok=True)
    samples = (np.clip(audio_array, -1.0, 1.0) * 32767).astype(np.int16)
    with wave.open(wav_path, "wb") as wav_file:
        wav_file.setnchannels(1)
        wav_file.setsampwidth(2)
        wav_file.setframerate(sample_rate)
        wav_file.writeframes(samples.tobytes())
    return wav_path


def create_silent_audio(duration=2.0, sample_rate=16000):
    """创建静音音频"""
    import numpy as np
    return np.zeros(int(duration * sample_rate), dtype=np.float32)


def text_to_speech_free(text, output_path):
    """文本转语音"""
    import subprocess
    
    try:
        import edge_tts
        import asyncio
        
        async def generate_audio():
            communicate = edge_tts.Communicate(text, "zh-CN-XiaoxiaoNeural")
            await communicate.save(output_path)
        
        asyncio.run(generate_audio())
        
        wav_path = output_path.replace(".mp3", ".wav")
        cmd = [
            "ffmpeg", "-y",
            "-i", output_path,
            "-ar", "16000",
            "-ac", "1",
            wav_path
        ]
        subprocess.run(cmd, check=True, capture_output=True)
        
        os.remove(output_path)
        return wav_path
    except ImportError:
        logger.warning("edge-tts 未安装，使用 gTTS")
        from gtts import gTTS
        
        tts = gTTS(text=text, lang='zh-cn')
        tts.save(output_path)
        
        wav_path = output_path.replace(".mp3", ".wav")
        cmd = [
            "ffmpeg", "-y",
            "-i", output_path,
            "-ar", "16000",
            "-ac", "1",
            wav_path
        ]
        subprocess.run(cmd, check=True, capture_output=True)
        
        os.remove(output_path)
        return wav_path


def notify_backend_new_segment(video_path):
    """通知Java后端有新视频段"""
    if callback_url:
        try:
            response = requests.post(
                callback_url,
                json={"path": video_path},
                timeout=5
            )
            logger.info(f"通知后端新视频段: {video_path}, 状态: {response.status_code}")
        except Exception as e:
            logger.warning(f"通知后端失败: {e}")


def notify_backend_generation_complete(final_video=None):
    """通知Java后端视频生成完成"""
    if callback_url:
        try:
            complete_url = callback_url.replace("/new-segment", "/generation-complete")
            data = {}
            if final_video:
                data["final_video"] = final_video
            response = requests.post(
                complete_url,
                json=data,
                timeout=5
            )
            logger.info(f"通知后端视频生成完成, 状态: {response.status_code}")
        except Exception as e:
            logger.warning(f"通知后端生成完成失败: {e}")


@app.route('/health', methods=['GET'])
def health():
    """健康检查"""
    return jsonify({
        "status": "ok",
        "flashhead_available": FLASHHEAD_AVAILABLE,
        "timestamp": datetime.now().isoformat()
    })


@app.route('/set-callback', methods=['POST'])
def set_callback():
    """设置回调URL"""
    global callback_url
    data = request.json
    callback_url = data.get('url')
    logger.info(f"设置回调URL: {callback_url}")
    return jsonify({"status": "success"})


@app.route('/tts', methods=['POST'])
def tts():
    """文本转语音"""
    try:
        data = request.json
        text = data.get('text', '')
        
        if not text:
            return jsonify({"error": "文本不能为空"}), 400
        
        # 使用通用目录存放 TTS 音频
        tts_temp_dir = os.path.join("/home/yukun/SoulX-FlashHead/chat_results", "tts")
        os.makedirs(tts_temp_dir, exist_ok=True)
        output_path = os.path.join(tts_temp_dir, "tts_audio.mp3")
        
        wav_path = text_to_speech_free(text, output_path)
        
        return send_file(wav_path, mimetype='audio/wav')
    except Exception as e:
        logger.error(f"TTS 错误: {e}")
        return jsonify({"error": str(e)}), 500


@app.route('/initialize', methods=['POST'])
def initialize():
    """初始化模型"""
    global pipeline, loaded_ckpt_dir, loaded_wav2vec_dir, loaded_model_type
    
    if not FLASHHEAD_AVAILABLE:
        return jsonify({"error": "FlashHead 模块不可用"}), 500
    
    try:
        data = request.json
        ckpt_dir = data.get('ckpt_dir', 'models/SoulX-FlashHead-1_3B')
        wav2vec_dir = data.get('wav2vec_dir', 'models/wav2vec2-base-960h')
        model_type = data.get('model_type', 'lite')
        cond_image = data.get('cond_image', 'examples/girl.png')
        seed = data.get('seed', 9999)
        use_face_crop = data.get('use_face_crop', False)
        
        if (
            pipeline is None
            or loaded_ckpt_dir != ckpt_dir
            or loaded_wav2vec_dir != wav2vec_dir
            or loaded_model_type != model_type
        ):
            logger.info(f"加载模型: ckpt_dir={ckpt_dir}, wav2vec_dir={wav2vec_dir}")
            pipeline = get_pipeline(
                world_size=1,
                ckpt_dir=ckpt_dir,
                model_type=model_type,
                wav2vec_dir=wav2vec_dir,
            )
            loaded_ckpt_dir = ckpt_dir
            loaded_wav2vec_dir = wav2vec_dir
            loaded_model_type = model_type
        
        base_seed = int(seed) if seed >= 0 else 9999
        get_base_data(
            pipeline,
            cond_image_path_or_dir=cond_image,
            base_seed=base_seed,
            use_face_crop=use_face_crop,
        )
        
        return jsonify({"status": "initialized"})
    except Exception as e:
        logger.error(f"初始化错误: {e}")
        return jsonify({"error": str(e)}), 500


@app.route('/generate-video-streaming', methods=['POST'])
def generate_video_streaming():
    """流式生成视频 - 使用异步线程+队列实现真正的流式"""
    if not FLASHHEAD_AVAILABLE:
        return jsonify({"error": "FlashHead 模块不可用"}), 500
    
    try:
        import numpy as np
        import torch
        import librosa
        
        logger.info(f"收到generate-video-streaming请求")
        data = request.json
        logger.info(f"请求数据: {data}")
        
        audio_path = data.get('audio_path')
        cond_image = data.get('cond_image', 'examples/girl.png')
        ckpt_dir = data.get('ckpt_dir', 'models/SoulX-FlashHead-1_3B')
        wav2vec_dir = data.get('wav2vec_dir', 'models/wav2vec2-base-960h')
        model_type = data.get('model_type', 'lite')
        seed = data.get('seed', 9999)
        use_face_crop = data.get('use_face_crop', False)
        stream_id = data.get('stream_id', datetime.now().strftime("%Y%m%d-%H%M%S"))
        use_streaming_callback = data.get('use_streaming_callback', False)
        
        logger.info(f"audio_path: {audio_path}, exists: {os.path.exists(audio_path) if audio_path else 'None'}")
        
        if not audio_path or not os.path.exists(audio_path):
            return jsonify({"error": "音频文件不存在", "audio_path": audio_path}), 400
        
        global pipeline, loaded_ckpt_dir, loaded_wav2vec_dir, loaded_model_type
        
        if (
            pipeline is None
            or loaded_ckpt_dir != ckpt_dir
            or loaded_wav2vec_dir != wav2vec_dir
            or loaded_model_type != model_type
        ):
            logger.info(f"加载模型: ckpt_dir={ckpt_dir}, wav2vec_dir={wav2vec_dir}")
            pipeline = get_pipeline(
                world_size=1,
                ckpt_dir=ckpt_dir,
                model_type=model_type,
                wav2vec_dir=wav2vec_dir,
            )
            loaded_ckpt_dir = ckpt_dir
            loaded_wav2vec_dir = wav2vec_dir
            loaded_model_type = model_type
        
        base_seed = int(seed) if seed >= 0 else 9999
        get_base_data(
            pipeline,
            cond_image_path_or_dir=cond_image,
            base_seed=base_seed,
            use_face_crop=use_face_crop,
        )
        
        infer_params = get_infer_params()
        sample_rate = infer_params["sample_rate"]
        tgt_fps = infer_params["tgt_fps"]
        cached_audio_duration = infer_params["cached_audio_duration"]
        frame_num = infer_params["frame_num"]
        motion_frames_num = infer_params["motion_frames_num"]
        slice_len = frame_num - motion_frames_num
        
        human_speech_array_all, _ = librosa.load(audio_path, sr=sample_rate, mono=True)
        human_speech_array_slice_len = slice_len * sample_rate // tgt_fps
        
        remainder = len(human_speech_array_all) % human_speech_array_slice_len
        if remainder > 0:
            pad_length = human_speech_array_slice_len - remainder
            human_speech_array_all = np.concatenate(
                [human_speech_array_all, np.zeros(pad_length, dtype=human_speech_array_all.dtype)]
            )
        human_speech_array_slices = human_speech_array_all.reshape(-1, human_speech_array_slice_len)
        total_chunks = len(human_speech_array_slices)
        
        if total_chunks == 0:
            return jsonify({"error": "音频太短"}), 400
        
        stream_dir = os.path.join("chat_results", "stream_preview")
        os.makedirs(stream_dir, exist_ok=True)
        timestamp = datetime.now().strftime("%Y%m%d-%H%M%S-%f")[:-3]
        accumulated = []
        
        # 复制一份音频文件，避免原文件被删除
        audio_copy_path = os.path.join(stream_dir, f"audio_{stream_id}.wav")
        import shutil
        shutil.copy2(audio_path, audio_copy_path)
        audio_path = audio_copy_path
        
        segment_audio_paths = {}
        num_segments = (total_chunks + CHUNKS_PER_SEGMENT - 1) // CHUNKS_PER_SEGMENT
        for segment_id in range(num_segments):
            start = segment_id * CHUNKS_PER_SEGMENT
            end = min(start + CHUNKS_PER_SEGMENT, total_chunks)
            audio_concat = np.concatenate(
                [human_speech_array_slices[i] for i in range(start, end)]
            )
            segment_audio_name = f"audio_{stream_id}_seg_{segment_id:04d}.wav"
            segment_audio_path = os.path.join(stream_dir, segment_audio_name)
            _save_chunk_audio_to_wav(
                audio_concat,
                segment_audio_path,
                sample_rate=sample_rate,
            )
            segment_audio_paths[segment_id] = segment_audio_path
        
        res_queue = queue.Queue()
        segment_paths = []
        
        def inference_worker():
            audio_dq = deque([0.0] * (sample_rate * cached_audio_duration), 
                            maxlen=sample_rate * cached_audio_duration)
            cached_audio_length_sum = sample_rate * cached_audio_duration
            audio_end_idx = cached_audio_duration * tgt_fps
            audio_start_idx = audio_end_idx - frame_num
            
            for chunk_idx, human_speech_array in enumerate(human_speech_array_slices):
                audio_dq.extend(human_speech_array.tolist())
                audio_array = np.array(audio_dq)
                audio_embedding = get_audio_embedding(pipeline, audio_array, audio_start_idx, audio_end_idx)
                torch.cuda.synchronize()
                start_time = time.time()
                video = run_pipeline(pipeline, audio_embedding)
                video = video[motion_frames_num:]
                torch.cuda.synchronize()
                logger.info(f"Infer chunk-{chunk_idx} done, cost time: {time.time() - start_time:.2f}s")
                chunk_frames_np = video.cpu().numpy()
                res_queue.put((chunk_idx, chunk_frames_np))
            res_queue.put(None)
        
        worker_thread = threading.Thread(target=inference_worker)
        worker_thread.start()
        
        frame_buffer = []
        while True:
            item = res_queue.get()
            if item is None:
                break
            chunk_idx, chunk_frames_np = item
            chunk_frames = torch.from_numpy(chunk_frames_np)
            accumulated.append(chunk_frames)
            frame_buffer.append(chunk_frames)
            if len(frame_buffer) == CHUNKS_PER_SEGMENT:
                segment_id = (chunk_idx + 1 - CHUNKS_PER_SEGMENT) // CHUNKS_PER_SEGMENT
                segment_audio_path = segment_audio_paths[segment_id]
                segment_path = os.path.join(
                    stream_dir, f"preview_{stream_id}_seg_{segment_id:04d}.mp4"
                )
                save_video_with_audio(
                    frame_buffer,
                    segment_path,
                    segment_audio_path,
                    fps=tgt_fps,
                )
                segment_paths.append(os.path.abspath(segment_path))
                
                if use_streaming_callback:
                    notify_backend_new_segment(os.path.abspath(segment_path))
                
                frame_buffer = []
        
        if frame_buffer:
            segment_id = num_segments - 1
            segment_audio_path = segment_audio_paths[segment_id]
            segment_path = os.path.join(
                stream_dir, f"preview_{stream_id}_seg_{segment_id:04d}.mp4"
            )
            save_video_with_audio(
                frame_buffer,
                segment_path,
                segment_audio_path,
                fps=tgt_fps,
            )
            segment_paths.append(os.path.abspath(segment_path))
            
            if use_streaming_callback:
                notify_backend_new_segment(os.path.abspath(segment_path))
        
        worker_thread.join()
        
        # 无论同步和异步模式都生成最终视频
        output_dir = "chat_results"
        os.makedirs(output_dir, exist_ok=True)
        final_filename = f"res_{stream_id}.mp4"
        final_path = os.path.join(output_dir, final_filename)
        save_video_with_audio(accumulated, final_path, audio_path, fps=tgt_fps)
        
        if use_streaming_callback:
            notify_backend_generation_complete(os.path.abspath(final_path))
            return jsonify({
                "status": "success",
                "stream_id": stream_id,
                "segments": segment_paths,
                "final_video": os.path.abspath(final_path)
            })
        
        # 同步模式响应
        return jsonify({
            "status": "success",
            "stream_id": stream_id,
            "segments": segment_paths,
            "final_video": os.path.abspath(final_path)
        })
    except Exception as e:
        logger.error(f"流式生成视频错误: {e}")
        return jsonify({"error": str(e)}), 500


@app.route('/generate-idle-video', methods=['POST'])
def generate_idle_video():
    """
    生成空闲视频 - 用于生成没有音频输入时的自然面部动作视频
    
    主要功能：
    - 根据条件图像生成带有微表情和自然动作的空闲视频
    - 使用静音音频作为输入驱动面部动作
    - 逐段生成并通过回调发送给后端，保证实时性
    
    请求参数：
    - duration: 视频总时长（秒），默认3.0
    - cond_image: 条件图像路径，默认 examples/girl.png
    - ckpt_dir: 模型检查点目录
    - wav2vec_dir: Wav2Vec模型目录
    - model_type: 模型类型（lite/full）
    - seed: 随机种子
    - use_face_crop: 是否使用人脸裁剪
    - use_streaming_callback: 是否使用流式回调，默认False
    - stream_id: 流ID，用于标识此次生成
    
    返回：
    - 包含生成视频路径的JSON响应
    """
    if not FLASHHEAD_AVAILABLE:
        return jsonify({"error": "FlashHead 模块不可用"}), 500
    
    try:
        import numpy as np
        import torch
        
        data = request.json
        duration = data.get('duration', 3.0)
        cond_image = data.get('cond_image', 'examples/girl.png')
        ckpt_dir = data.get('ckpt_dir', 'models/SoulX-FlashHead-1_3B')
        wav2vec_dir = data.get('wav2vec_dir', 'models/wav2vec2-base-960h')
        model_type = data.get('model_type', 'lite')
        seed = data.get('seed', 9999)
        use_face_crop = data.get('use_face_crop', False)
        use_streaming_callback = data.get('use_streaming_callback', False)
        stream_id = data.get('stream_id', datetime.now().strftime("%Y%m%d-%H%M%S"))
        
        global pipeline, loaded_ckpt_dir, loaded_wav2vec_dir, loaded_model_type
        
        if (
            pipeline is None
            or loaded_ckpt_dir != ckpt_dir
            or loaded_wav2vec_dir != wav2vec_dir
            or loaded_model_type != model_type
        ):
            logger.info(f"加载模型: ckpt_dir={ckpt_dir}, wav2vec_dir={wav2vec_dir}")
            pipeline = get_pipeline(
                world_size=1,
                ckpt_dir=ckpt_dir,
                model_type=model_type,
                wav2vec_dir=wav2vec_dir,
            )
            loaded_ckpt_dir = ckpt_dir
            loaded_wav2vec_dir = wav2vec_dir
            loaded_model_type = model_type
        
        base_seed = int(seed) if seed >= 0 else 9999
        get_base_data(
            pipeline,
            cond_image_path_or_dir=cond_image,
            base_seed=base_seed,
            use_face_crop=use_face_crop,
        )
        
        infer_params = get_infer_params()
        sample_rate = infer_params["sample_rate"]
        tgt_fps = infer_params["tgt_fps"]
        cached_audio_duration = infer_params["cached_audio_duration"]
        frame_num = infer_params["frame_num"]
        motion_frames_num = infer_params["motion_frames_num"]
        slice_len = frame_num - motion_frames_num
        
        silent_audio = create_silent_audio(duration=duration, sample_rate=sample_rate)
        human_speech_array_slice_len = slice_len * sample_rate // tgt_fps
        remainder = len(silent_audio) % human_speech_array_slice_len
        if remainder > 0:
            pad_length = human_speech_array_slice_len - remainder
            silent_audio = np.concatenate(
                [silent_audio, np.zeros(pad_length, dtype=silent_audio.dtype)]
            )
        human_speech_array_slices = silent_audio.reshape(-1, human_speech_array_slice_len)
        
        if len(human_speech_array_slices) == 0:
            return jsonify({"error": "音频太短"}), 400
        
        stream_dir = os.path.join("chat_results", "idle")
        os.makedirs(stream_dir, exist_ok=True)
        timestamp = datetime.now().strftime("%Y%m%d-%H%M%S-%f")[:-3]
        accumulated = []
        segment_paths = []
        
        # 计算每个片段的时长（秒）
        segment_duration = slice_len / tgt_fps
        logger.info(f"每个片段时长: {segment_duration:.2f}秒, 总片段数: {len(human_speech_array_slices)}")
        
        # 准备每个片段的静音音频
        segment_audio_paths = {}
        num_segments = len(human_speech_array_slices)
        for segment_id in range(num_segments):
            audio_concat = human_speech_array_slices[segment_id]
            segment_audio_name = f"idle_audio_{stream_id}_seg_{segment_id:04d}.wav"
            segment_audio_path = os.path.join(stream_dir, segment_audio_name)
            _save_chunk_audio_to_wav(
                audio_concat,
                segment_audio_path,
                sample_rate=sample_rate,
            )
            segment_audio_paths[segment_id] = segment_audio_path
        
        cached_audio_length_sum = sample_rate * cached_audio_duration
        audio_end_idx = cached_audio_duration * tgt_fps
        audio_start_idx = audio_end_idx - frame_num
        
        audio_dq = deque([0.0] * cached_audio_length_sum, maxlen=cached_audio_length_sum)
        frame_buffer = []
        
        for chunk_idx, human_speech_array in enumerate(human_speech_array_slices):
            audio_dq.extend(human_speech_array.tolist())
            audio_array = np.array(audio_dq)
            audio_embedding = get_audio_embedding(pipeline, audio_array, audio_start_idx, audio_end_idx)
            torch.cuda.synchronize()
            start_time = time.time()
            video = run_pipeline(pipeline, audio_embedding)
            video = video[motion_frames_num:]
            torch.cuda.synchronize()
            logger.info(f"空闲视频 chunk-{chunk_idx} 推理完成, 耗时: {time.time() - start_time:.2f}s")
            chunk_frames_np = video.cpu().numpy()
            chunk_frames = torch.from_numpy(chunk_frames_np)
            accumulated.append(chunk_frames)
            frame_buffer.append(chunk_frames)
            
            # 每个chunk生成一个视频片段
            segment_id = chunk_idx
            segment_audio_path = segment_audio_paths[segment_id]
            segment_path = os.path.join(
                stream_dir, f"idle_{stream_id}_seg_{segment_id:04d}.mp4"
            )
            save_video_with_audio(
                frame_buffer,
                segment_path,
                segment_audio_path,
                fps=tgt_fps,
            )
            segment_abs_path = os.path.abspath(segment_path)
            segment_paths.append(segment_abs_path)
            
            # 如果启用了流式回调，立即通知后端
            if use_streaming_callback:
                notify_backend_new_segment(segment_abs_path)
            
            frame_buffer = []
        
        logger.info(f"空闲视频生成完成, 总片段数: {len(segment_paths)}")
        
        if use_streaming_callback:
            notify_backend_generation_complete()
            return jsonify({
                "status": "success",
                "stream_id": stream_id,
                "segments": segment_paths
            })
        
        # 非流式模式，生成完整视频
        # 在 session_id 目录下创建音频文件
        session_temp_dir = os.path.join("/home/yukun/SoulX-FlashHead/chat_results", stream_id, "audio")
        os.makedirs(session_temp_dir, exist_ok=True)
        silent_audio_path = os.path.join(session_temp_dir, "silent.wav")
        _save_chunk_audio_to_wav(create_silent_audio(duration=duration), silent_audio_path, sample_rate)
        
        idle_video_path = os.path.join(stream_dir, f"idle_{timestamp}.mp4")
        save_video_with_audio(accumulated, idle_video_path, silent_audio_path, fps=tgt_fps)
        
        logger.info(f"生成空闲视频: {idle_video_path}")
        
        return jsonify({
            "status": "success",
            "video_path": os.path.abspath(idle_video_path)
        })
    except Exception as e:
        logger.error(f"生成空闲视频错误: {e}")
        return jsonify({"error": str(e)}), 500


@app.route('/generate-video-hls', methods=['POST'])
def generate_video_hls():
    """
    HLS 流式生成视频（队列模式）
    使用 FFmpeg 实时转封装为 HLS 格式，通过 HTTP 推送 TS 片段到后端
    所有视频生成任务会进入队列，串行执行，避免并发冲突
    
    请求参数：
    - audio_path: 音频文件路径
    - cond_image: 条件图像路径
    - ckpt_dir: 模型检查点目录
    - wav2vec_dir: Wav2Vec模型目录
    - model_type: 模型类型（lite/full）
    - seed: 随机种子
    - use_face_crop: 是否使用人脸裁剪
    - stream_id: 流ID，用于标识此次生成
    - backend_url: 后端服务地址，默认 http://localhost:8080
    
    返回：
    - 包含 HLS 播放地址的 JSON 响应（立即返回，任务在后台队列中执行）
    """
    if not FLASHHEAD_AVAILABLE:
        return jsonify({"error": "FlashHead 模块不可用"}), 500
    
    try:
        logger.info(f"收到 generate-video-hls 请求")
        data = request.json
        
        audio_path = data.get('audio_path')
        cond_image = data.get('cond_image', 'examples/girl.png')
        ckpt_dir = data.get('ckpt_dir', 'models/SoulX-FlashHead-1_3B')
        wav2vec_dir = data.get('wav2vec_dir', 'models/wav2vec2-base-960h')
        model_type = data.get('model_type', 'lite')
        seed = data.get('seed', 9999)
        use_face_crop = data.get('use_face_crop', False)
        stream_id = data.get('stream_id', datetime.now().strftime("%Y%m%d-%H%M%S"))
        backend_url = data.get('backend_url', 'http://localhost:8080')
        start_sequence = data.get('start_sequence', 0)
        
        logger.info(f"audio_path: {audio_path}, exists: {os.path.exists(audio_path) if audio_path else 'None'}")
        
        if not audio_path or not os.path.exists(audio_path):
            return jsonify({"error": "音频文件不存在", "audio_path": audio_path}), 400
        
        # 准备任务参数
        task_params = {
            'audio_path': audio_path,
            'cond_image': cond_image,
            'ckpt_dir': ckpt_dir,
            'wav2vec_dir': wav2vec_dir,
            'model_type': model_type,
            'seed': seed,
            'use_face_crop': use_face_crop,
            'stream_id': stream_id,
            'backend_url': backend_url,
            'start_sequence': start_sequence
        }
        
        # 创建任务并提交到队列
        task = VideoGenerationTask(task_type='hls_video', params=task_params)
        task_id = task_queue.submit(task)
        
        # 立即返回 HLS 播放地址（任务在后台队列中异步执行）
        hls_url = f"{backend_url}/api/hls/{stream_id}/playlist.m3u8"
        
        return jsonify({
            "status": "success",
            "stream_id": stream_id,
            "hls_url": hls_url,
            "message": "HLS 视频生成任务已加入队列",
            "task_id": task_id,
            "queue_size": task_queue.queue.qsize()
        })
        
    except Exception as e:
        logger.error(f"HLS 流式生成视频错误: {e}")
        import traceback
        logger.error(traceback.format_exc())
        return jsonify({"error": str(e)}), 500


@app.route('/generate-idle-video-hls', methods=['POST'])
def generate_idle_video_hls():
    """
    HLS 流式生成空闲视频（队列模式）
    所有视频生成任务会进入队列，串行执行，避免并发冲突
    
    请求参数：
    - duration: 视频总时长（秒），默认3.0
    - cond_image: 条件图像路径
    - ckpt_dir: 模型检查点目录
    - wav2vec_dir: Wav2Vec模型目录
    - model_type: 模型类型（lite/full）
    - seed: 随机种子
    - use_face_crop: 是否使用人脸裁剪
    - stream_id: 流ID，用于标识此次生成
    - backend_url: 后端服务地址，默认 http://localhost:8080
    
    返回：
    - 包含 HLS 播放地址的 JSON 响应（立即返回，任务在后台队列中执行）
    """
    if not FLASHHEAD_AVAILABLE:
        return jsonify({"error": "FlashHead 模块不可用"}), 500
    
    try:
        logger.info(f"收到 generate-idle-video-hls 请求")
        data = request.json
        
        duration = data.get('duration', 3.0)
        cond_image = data.get('cond_image', 'examples/girl.png')
        ckpt_dir = data.get('ckpt_dir', 'models/SoulX-FlashHead-1_3B')
        wav2vec_dir = data.get('wav2vec_dir', 'models/wav2vec2-base-960h')
        model_type = data.get('model_type', 'lite')
        seed = data.get('seed', 9999)
        use_face_crop = data.get('use_face_crop', False)
        stream_id = data.get('stream_id', datetime.now().strftime("%Y%m%d-%H%M%S"))
        backend_url = data.get('backend_url', 'http://localhost:8080')
        start_sequence = data.get('start_sequence', 0)
        
        # 准备任务参数
        task_params = {
            'duration': duration,
            'cond_image': cond_image,
            'ckpt_dir': ckpt_dir,
            'wav2vec_dir': wav2vec_dir,
            'model_type': model_type,
            'seed': seed,
            'use_face_crop': use_face_crop,
            'stream_id': stream_id,
            'backend_url': backend_url,
            'start_sequence': start_sequence
        }
        
        # 创建任务并提交到队列
        task = VideoGenerationTask(task_type='hls_idle', params=task_params)
        task_id = task_queue.submit(task)
        
        # 立即返回 HLS 播放地址（任务在后台队列中异步执行）
        hls_url = f"{backend_url}/api/hls/{stream_id}/playlist.m3u8"
        
        return jsonify({
            "status": "success",
            "stream_id": stream_id,
            "hls_url": hls_url,
            "message": "HLS 空闲视频生成任务已加入队列",
            "task_id": task_id,
            "queue_size": task_queue.queue.qsize()
        })
        
    except Exception as e:
        logger.error(f"HLS 空闲视频生成错误: {e}")
        import traceback
        logger.error(traceback.format_exc())
        return jsonify({"error": str(e)}), 500


if __name__ == '__main__':
    logger.info("启动 FlashHead Python 服务...")
    app.run(host='0.0.0.0', port=5000, debug=True, threaded=True)
