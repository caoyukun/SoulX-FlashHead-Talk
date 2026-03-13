"""
SoulX-FlashHead 实时聊天应用
优化版本：保持当前视频显示，后台准备新视频

代码结构说明：
1. 全局变量：存储模型、视频状态、聊天历史等
2. 视频/音频处理工具函数：处理视频帧、音频保存等
3. LLM调用函数：调用火山引擎API获取回复
4. TTS函数：将文本转为语音
5. 视频生成函数：根据音频生成数字人视频
6. 主聊天函数：处理用户消息，生成回复视频
7. 初始化函数：应用启动时加载模型，生成初始视频
8. Gradio UI：构建用户界面
"""
import gradio as gr
import os
import torch
import numpy as np
import time
import wave
import imageio
import librosa
import subprocess
import queue
import threading
import requests
from datetime import datetime
from collections import deque
from loguru import logger
import tempfile
from dotenv import load_dotenv

from flash_head.inference import (
    get_pipeline,
    get_base_data,
    get_infer_params,
    get_audio_embedding,
    run_pipeline,
)

# 加载环境变量（从.env文件）
load_dotenv()

# ========== 全局变量 ==========
# 每段视频包含多少个chunk（用于流式播放）
CHUNKS_PER_SEGMENT = 3

# FlashHead模型相关
pipeline = None  # 模型管道
loaded_ckpt_dir = None  # 已加载的checkpoint目录
loaded_wav2vec_dir = None  # 已加载的wav2vec目录
loaded_model_type = None  # 已加载的模型类型

# 聊天相关
chat_history = []  # 聊天历史记录
initial_video_path = None  # 初始视频路径
current_video_state = None  # 当前显示的视频状态


# ========== 视频/音频处理工具函数 ==========
def _write_frames_to_mp4(frames_list, video_path, fps):
    """
    将视频帧列表写入MP4文件（仅视频轨）
    
    参数:
        frames_list: 视频帧列表
        video_path: 输出视频路径
        fps: 帧率
    """
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
            frames_np = frames.numpy().astype(np.uint8)
            for i in range(frames_np.shape[0]):
                writer.append_data(frames_np[i, :, :, :])
    return video_path


def save_video_with_audio(frames_list, video_path, audio_path, fps):
    """
    将视频帧和音频合并为完整的MP4文件
    
    参数:
        frames_list: 视频帧列表
        video_path: 输出视频路径
        audio_path: 音频文件路径
        fps: 帧率
    """
    # 先写入临时视频文件（无音频）
    temp_path = video_path.replace(".mp4", "_temp.mp4")
    _write_frames_to_mp4(frames_list, temp_path, fps)
    try:
        # 使用ffmpeg合并视频和音频
        cmd = [
            "ffmpeg", "-y",
            "-i", temp_path,
            "-i", audio_path,
            "-c:v", "copy",
            "-c:a", "aac",
            video_path,
        ]
        subprocess.run(cmd, check=True, capture_output=True)
    finally:
        # 清理临时文件
        if os.path.exists(temp_path):
            os.remove(temp_path)
    return video_path


def _save_chunk_audio_to_wav(audio_array, wav_path, sample_rate=16000):
    """
    将音频数组保存为WAV文件
    
    参数:
        audio_array: 音频数组（float32格式，范围[-1, 1]）
        wav_path: 输出WAV路径
        sample_rate: 采样率
    """
    os.makedirs(os.path.dirname(wav_path) or ".", exist_ok=True)
    # 转换为int16格式
    samples = (np.clip(audio_array, -1.0, 1.0) * 32767).astype(np.int16)
    with wave.open(wav_path, "wb") as wav_file:
        wav_file.setnchannels(1)  # 单声道
        wav_file.setsampwidth(2)  # 16位
        wav_file.setframerate(sample_rate)  # 采样率
        wav_file.writeframes(samples.tobytes())
    return wav_path


# ========== LLM调用函数 ==========
def get_volcengine_response(user_message, api_key):
    """
    调用火山引擎 Coding Plan API获取LLM回复
    
    参数:
        user_message: 用户消息
        api_key: 火山引擎API Key
    
    返回:
        LLM回复文本
    """
    # 火山引擎 Coding Plan API地址（兼容OpenAI协议）
    url = "https://ark.cn-beijing.volces.com/api/coding/v3/chat/completions"
    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {api_key}"
    }
    
    # 系统提示词
    system_prompt = "你是一个友好、智能的数字人助手。请用自然、亲切的语气回答用户的问题，回复要简洁明了。"
    
    # 构建消息列表
    messages = [
        {"role": "system", "content": system_prompt}
    ]
    
    # 添加最近10条历史消息
    for msg in chat_history[-10:]:
        messages.append({"role": "user", "content": msg["user"]})
        messages.append({"role": "assistant", "content": msg["assistant"]})
    
    # 添加当前用户消息
    messages.append({"role": "user", "content": user_message})
    
    # 请求数据
    data = {
        "model": "ark-code-latest",  # 使用自动选择模型模式
        "messages": messages,
        "max_tokens": 1000,
        "temperature": 0.7
    }
    
    try:
        response = requests.post(url, headers=headers, json=data, timeout=30)
        response.raise_for_status()
        result = response.json()
        return result["choices"][0]["message"]["content"]
    except Exception as e:
        logger.error(f"Volcengine API 调用失败: {e}")
        return f"抱歉，我暂时无法回答你的问题。错误信息: {str(e)}"


# ========== TTS函数 ==========
def text_to_speech_free(text, output_path):
    """
    将文本转换为语音（使用edge-tts或gTTS）
    
    参数:
        text: 要转换的文本
        output_path: 输出MP3路径
    
    返回:
        转换后的WAV文件路径（16kHz, 单声道）
    """
    try:
        # 优先使用edge-tts（质量更好）
        import edge_tts
        import asyncio
        
        async def generate_audio():
            communicate = edge_tts.Communicate(text, "zh-CN-XiaoxiaoNeural")
            await communicate.save(output_path)
        
        asyncio.run(generate_audio())
        
        # 转换为16kHz, 单声道WAV
        wav_path = output_path.replace(".mp3", ".wav")
        cmd = [
            "ffmpeg", "-y",
            "-i", output_path,
            "-ar", "16000",
            "-ac", "1",
            wav_path
        ]
        subprocess.run(cmd, check=True, capture_output=True)
        
        # 清理临时MP3文件
        os.remove(output_path)
        return wav_path
    except ImportError:
        # edge-tts未安装，使用gTTS作为备选
        logger.warning("edge-tts 未安装，使用 gTTS 作为备选")
        try:
            from gtts import gTTS
            
            tts = gTTS(text=text, lang='zh-cn')
            tts.save(output_path)
            
            # 转换为16kHz, 单声道WAV
            wav_path = output_path.replace(".mp3", ".wav")
            cmd = [
                "ffmpeg", "-y",
                "-i", output_path,
                "-ar", "16000",
                "-ac", "1",
                wav_path
            ]
            subprocess.run(cmd, check=True, capture_output=True)
            
            # 清理临时MP3文件
            os.remove(output_path)
            return wav_path
        except Exception as e:
            logger.error(f"TTS 生成失败: {e}")
            raise
    except Exception as e:
        logger.error(f"TTS 生成失败: {e}")
        raise


# ========== 辅助函数 ==========
def create_silent_audio(duration=2.0, sample_rate=16000):
    """
    创建静音音频
    
    参数:
        duration: 时长（秒）
        sample_rate: 采样率
    
    返回:
        静音音频数组
    """
    silent_audio = np.zeros(int(duration * sample_rate), dtype=np.float32)
    return silent_audio


# ========== 空闲视频生成函数 ==========
def generate_idle_video(duration=5.0, cond_image=None, seed=None, use_face_crop=None):
    """
    生成空闲状态的视频（数字人不说话，保持活动）
    
    参数:
        duration: 视频时长（秒）
        cond_image: 数字人照片
        seed: 随机种子
        use_face_crop: 是否使用人脸裁剪
    
    返回:
        空闲视频路径
    """
    global pipeline
    
    if pipeline is None:
        return None
    
    # 获取推理参数
    infer_params = get_infer_params()
    sample_rate = infer_params["sample_rate"]
    tgt_fps = infer_params["tgt_fps"]
    cached_audio_duration = infer_params["cached_audio_duration"]
    frame_num = infer_params["frame_num"]
    motion_frames_num = infer_params["motion_frames_num"]
    slice_len = frame_num - motion_frames_num
    
    # 创建静音音频
    silent_audio = create_silent_audio(duration=duration, sample_rate=sample_rate)
    
    # 处理音频，确保长度符合要求
    human_speech_array_slice_len = slice_len * sample_rate // tgt_fps
    remainder = len(silent_audio) % human_speech_array_slice_len
    if remainder > 0:
        pad_length = human_speech_array_slice_len - remainder
        silent_audio = np.concatenate(
            [silent_audio, np.zeros(pad_length, dtype=silent_audio.dtype)]
        )
    human_speech_array_slices = silent_audio.reshape(-1, human_speech_array_slice_len)
    
    if len(human_speech_array_slices) == 0:
        return None
    
    # 准备输出目录
    stream_dir = os.path.join("chat_results", "idle")
    os.makedirs(stream_dir, exist_ok=True)
    timestamp = datetime.now().strftime("%Y%m%d-%H%M%S-%f")[:-3]
    accumulated = []
    
    # 推理参数
    cached_audio_length_sum = sample_rate * cached_audio_duration
    audio_end_idx = cached_audio_duration * tgt_fps
    audio_start_idx = audio_end_idx - frame_num
    
    # 生成视频帧
    audio_dq = deque([0.0] * cached_audio_length_sum, maxlen=cached_audio_length_sum)
    for human_speech_array in human_speech_array_slices:
        audio_dq.extend(human_speech_array.tolist())
        audio_array = np.array(audio_dq)
        audio_embedding = get_audio_embedding(pipeline, audio_array, audio_start_idx, audio_end_idx)
        torch.cuda.synchronize()
        video = run_pipeline(pipeline, audio_embedding)
        video = video[motion_frames_num:]
        torch.cuda.synchronize()
        accumulated.append(torch.from_numpy(video.cpu().numpy()))
    
    # 保存视频
    temp_dir = tempfile.mkdtemp()
    silent_audio_path = os.path.join(temp_dir, "silent.wav")
    _save_chunk_audio_to_wav(create_silent_audio(duration=duration), silent_audio_path, sample_rate)
    
    idle_video_path = os.path.join(stream_dir, f"idle_{timestamp}.mp4")
    save_video_with_audio(accumulated, idle_video_path, silent_audio_path, fps=tgt_fps)
    
    logger.info(f"生成空闲视频: {idle_video_path}")
    return idle_video_path


# ========== 流式视频生成函数 ==========
def run_inference_streaming(
    ckpt_dir,
    wav2vec_dir,
    model_type,
    cond_image,
    audio_path,
    seed,
    use_face_crop,
):
    """
    流式生成数字人视频（根据音频）
    
    参数:
        ckpt_dir: 模型checkpoint目录
        wav2vec_dir: wav2vec模型目录
        model_type: 模型类型（pro/lite）
        cond_image: 数字人照片
        audio_path: 输入音频路径
        seed: 随机种子
        use_face_crop: 是否使用人脸裁剪
    
    生成:
        视频片段路径（流式输出）
    """
    global pipeline, loaded_ckpt_dir, loaded_wav2vec_dir, loaded_model_type

    # 检查是否需要重新加载模型
    if (
        pipeline is None
        or loaded_ckpt_dir != ckpt_dir
        or loaded_wav2vec_dir != wav2vec_dir
        or loaded_model_type != model_type
    ):
        logger.info(f"Loading pipeline with ckpt_dir={ckpt_dir}, wav2vec_dir={wav2vec_dir}")
        try:
            pipeline = get_pipeline(
                world_size=1,
                ckpt_dir=ckpt_dir,
                model_type=model_type,
                wav2vec_dir=wav2vec_dir,
            )
            loaded_ckpt_dir = ckpt_dir
            loaded_wav2vec_dir = wav2vec_dir
            loaded_model_type = model_type
        except Exception as e:
            logger.error(f"Failed to load model: {e}")
            raise gr.Error(f"加载模型失败: {e}")

    # 准备基础数据
    base_seed = int(seed) if seed >= 0 else 9999
    try:
        get_base_data(
            pipeline,
            cond_image_path_or_dir=cond_image,
            base_seed=base_seed,
            use_face_crop=use_face_crop,
        )
    except Exception as e:
        logger.error(f"Error in get_base_data: {e}")
        raise gr.Error(f"处理输入时出错: {e}")

    # 获取推理参数
    infer_params = get_infer_params()
    sample_rate = infer_params["sample_rate"]
    tgt_fps = infer_params["tgt_fps"]
    cached_audio_duration = infer_params["cached_audio_duration"]
    frame_num = infer_params["frame_num"]
    motion_frames_num = infer_params["motion_frames_num"]
    slice_len = frame_num - motion_frames_num

    # 加载音频
    try:
        human_speech_array_all, _ = librosa.load(audio_path, sr=sample_rate, mono=True)
    except Exception as e:
        raise gr.Error(f"加载音频文件失败: {e}")

    # 计算音频切片长度
    human_speech_array_slice_len = slice_len * sample_rate // tgt_fps

    # 准备输出目录
    stream_dir = os.path.join("chat_results", "stream_preview")
    os.makedirs(stream_dir, exist_ok=True)
    timestamp = datetime.now().strftime("%Y%m%d-%H%M%S-%f")[:-3]
    accumulated = []

    # 推理相关参数
    cached_audio_length_sum = sample_rate * cached_audio_duration
    audio_end_idx = cached_audio_duration * tgt_fps
    audio_start_idx = audio_end_idx - frame_num
    
    # 处理音频，确保长度符合要求
    remainder = len(human_speech_array_all) % human_speech_array_slice_len
    if remainder > 0:
        pad_length = human_speech_array_slice_len - remainder
        human_speech_array_all = np.concatenate(
            [human_speech_array_all, np.zeros(pad_length, dtype=human_speech_array_all.dtype)]
        )
    human_speech_array_slices = human_speech_array_all.reshape(-1, human_speech_array_slice_len)
    total_chunks = len(human_speech_array_slices)
    if total_chunks == 0:
        raise gr.Error("音频太短: 没有足够的 chunk 来生成视频。请使用更长的音频。")

    # 预保存音频片段
    segment_audio_paths = {}
    num_segments = (total_chunks + CHUNKS_PER_SEGMENT - 1) // CHUNKS_PER_SEGMENT
    for segment_id in range(num_segments):
        start = segment_id * CHUNKS_PER_SEGMENT
        end = min(start + CHUNKS_PER_SEGMENT, total_chunks)
        audio_concat = np.concatenate(
            [human_speech_array_slices[i] for i in range(start, end)]
        )
        segment_audio_name = f"audio_{timestamp}_seg_{segment_id:04d}.wav"
        segment_audio_path = os.path.join(stream_dir, segment_audio_name)
        _save_chunk_audio_to_wav(
            audio_concat,
            segment_audio_path,
            sample_rate=sample_rate,
        )
        segment_audio_paths[segment_id] = segment_audio_path
    logger.info(
        f"Pre-saved {num_segments} segment audios (every {CHUNKS_PER_SEGMENT} chunks) under {stream_dir}"
    )

    # 使用队列和线程实现流式推理
    res_queue = queue.Queue()

    def inference_worker():
        """后台推理线程"""
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
            logger.info(f"Infer chunk-{chunk_idx} done, cost time: {time.time() - start_time:.2f}s")
            chunk_frames_np = video.cpu().numpy()
            res_queue.put((chunk_idx, chunk_frames_np))
        res_queue.put(None)

    # 启动推理线程
    worker_thread = threading.Thread(target=inference_worker)
    worker_thread.start()
    logger.info("Inference worker thread started. Main will consume res_queue and yield video paths.")

    # 消费推理结果，生成视频片段
    frame_buffer = []
    while True:
        item = res_queue.get()
        if item is None:
            break
        chunk_idx, chunk_frames_np = item
        chunk_frames = torch.from_numpy(chunk_frames_np)
        accumulated.append(chunk_frames)
        frame_buffer.append(chunk_frames)
        # 每CHUNKS_PER_SEGMENT个chunk生成一个视频片段
        if len(frame_buffer) == CHUNKS_PER_SEGMENT:
            segment_id = (chunk_idx + 1 - CHUNKS_PER_SEGMENT) // CHUNKS_PER_SEGMENT
            segment_audio_path = segment_audio_paths[segment_id]
            segment_path = os.path.join(
                stream_dir, f"preview_{timestamp}_seg_{segment_id:04d}.mp4"
            )
            save_video_with_audio(
                frame_buffer,
                segment_path,
                segment_audio_path,
                fps=tgt_fps,
            )
            logger.info(
                f"Saved segment-{segment_id} (chunks {segment_id * CHUNKS_PER_SEGMENT}-{chunk_idx}) and yielding to frontend."
            )
            yield os.path.abspath(segment_path)
            frame_buffer = []

    # 处理剩余的帧
    if frame_buffer:
        segment_id = num_segments - 1
        segment_audio_path = segment_audio_paths[segment_id]
        segment_path = os.path.join(
            stream_dir, f"preview_{timestamp}_seg_{segment_id:04d}.mp4"
        )
        save_video_with_audio(
            frame_buffer,
            segment_path,
            segment_audio_path,
            fps=tgt_fps,
        )
        logger.info(
            f"Saved final segment-{segment_id} ({len(frame_buffer)} chunks) and yielding to frontend."
        )
        yield os.path.abspath(segment_path)

    worker_thread.join()

    if not accumulated:
        raise gr.Error("没有生成视频帧。请检查输入并重试。")

    # 保存最终完整视频
    output_dir = "chat_results"
    os.makedirs(output_dir, exist_ok=True)
    final_filename = f"res_{timestamp}.mp4"
    final_path = os.path.join(output_dir, final_filename)
    save_video_with_audio(accumulated, final_path, audio_path, fps=tgt_fps)
    logger.info(f"Saved to {final_path}")


# ========== 主聊天函数 ==========
def chat_with_digital_human(
    user_message,
    api_key,
    cond_image,
    ckpt_dir,
    wav2vec_dir,
    model_type,
    seed,
    use_face_crop,
    chat_history_state,
    current_video,
):
    """
    处理用户聊天消息，生成数字人回复视频
    
    参数:
        user_message: 用户消息
        api_key: API Key
        cond_image: 数字人照片
        ckpt_dir: 模型checkpoint目录
        wav2vec_dir: wav2vec模型目录
        model_type: 模型类型
        seed: 随机种子
        use_face_crop: 是否使用人脸裁剪
        chat_history_state: 聊天历史状态
        current_video: 当前显示的视频
    
    生成:
        (更新后的聊天历史, 视频路径)
    """
    global chat_history, current_video_state, initial_video_path
    
    # 获取API Key
    if not api_key:
        api_key = os.getenv("ARK_API_KEY", "")
    
    if not api_key:
        raise gr.Error("请先配置 ARK_API_KEY 环境变量或在界面中输入 API Key")
    
    # 空消息检查：直接返回当前状态
    if not user_message.strip():
        return chat_history_state, current_video
    
    # 1. 获取LLM回复
    assistant_message = get_volcengine_response(user_message, api_key)
    
    # 2. 文本转语音
    temp_dir = tempfile.mkdtemp()
    tts_output_path = os.path.join(temp_dir, "tts_audio.mp3")
    audio_path = text_to_speech_free(assistant_message, tts_output_path)
    
    # 3. 更新聊天历史
    chat_history.append({
        "user": user_message,
        "assistant": assistant_message
    })
    
    chat_history_state = chat_history_state or []
    chat_history_state.append((user_message, assistant_message))
    
    # 4. 生成回复视频（流式输出）
    video_generator = run_inference_streaming(
        ckpt_dir,
        wav2vec_dir,
        model_type,
        cond_image,
        audio_path,
        seed,
        use_face_crop,
    )
    
    # 流式输出回复视频
    for video_path in video_generator:
        current_video_state = video_path
        yield chat_history_state, video_path
    
    # 5. 生成并输出空闲视频，保持数字人活动
    idle_count = 0
    while idle_count < 3:
        try:
            idle_video = generate_idle_video(duration=5.0, cond_image=cond_image, seed=seed, use_face_crop=use_face_crop)
            if idle_video:
                current_video_state = idle_video
                yield chat_history_state, idle_video
                idle_count += 1
        except Exception as e:
            logger.error(f"生成空闲视频时出错: {e}")
            break


# ========== 初始化函数 ==========
def initialize_digital_human(
    cond_image,
    ckpt_dir,
    wav2vec_dir,
    model_type,
    seed,
    use_face_crop,
):
    """
    应用启动时初始化数字人：加载模型，生成初始视频
    
    参数:
        cond_image: 数字人照片
        ckpt_dir: 模型checkpoint目录
        wav2vec_dir: wav2vec模型目录
        model_type: 模型类型
        seed: 随机种子
        use_face_crop: 是否使用人脸裁剪
    
    返回:
        初始视频路径
    """
    global pipeline, loaded_ckpt_dir, loaded_wav2vec_dir, loaded_model_type, initial_video_path, current_video_state
    
    logger.info("初始化数字人模型...")
    
    # 1. 加载模型
    if (
        pipeline is None
        or loaded_ckpt_dir != ckpt_dir
        or loaded_wav2vec_dir != wav2vec_dir
        or loaded_model_type != model_type
    ):
        logger.info(f"Loading pipeline with ckpt_dir={ckpt_dir}, wav2vec_dir={wav2vec_dir}")
        try:
            pipeline = get_pipeline(
                world_size=1,
                ckpt_dir=ckpt_dir,
                model_type=model_type,
                wav2vec_dir=wav2vec_dir,
            )
            loaded_ckpt_dir = ckpt_dir
            loaded_wav2vec_dir = wav2vec_dir
            loaded_model_type = model_type
        except Exception as e:
            logger.error(f"Failed to load model: {e}")
            raise gr.Error(f"加载模型失败: {e}")
    
    # 2. 准备基础数据
    base_seed = int(seed) if seed >= 0 else 9999
    try:
        get_base_data(
            pipeline,
            cond_image_path_or_dir=cond_image,
            base_seed=base_seed,
            use_face_crop=use_face_crop,
        )
    except Exception as e:
        logger.error(f"Error in get_base_data: {e}")
        raise gr.Error(f"处理输入时出错: {e}")
    
    # 3. 获取推理参数
    infer_params = get_infer_params()
    sample_rate = infer_params["sample_rate"]
    tgt_fps = infer_params["tgt_fps"]
    cached_audio_duration = infer_params["cached_audio_duration"]
    frame_num = infer_params["frame_num"]
    motion_frames_num = infer_params["motion_frames_num"]
    slice_len = frame_num - motion_frames_num
    
    # 4. 创建静音音频，生成初始视频
    silent_audio = create_silent_audio(duration=1.0, sample_rate=sample_rate)
    
    # 处理音频
    human_speech_array_slice_len = slice_len * sample_rate // tgt_fps
    remainder = len(silent_audio) % human_speech_array_slice_len
    if remainder > 0:
        pad_length = human_speech_array_slice_len - remainder
        silent_audio = np.concatenate(
            [silent_audio, np.zeros(pad_length, dtype=silent_audio.dtype)]
        )
    human_speech_array_slices = silent_audio.reshape(-1, human_speech_array_slice_len)
    
    # 准备输出目录
    stream_dir = os.path.join("chat_results", "initial")
    os.makedirs(stream_dir, exist_ok=True)
    timestamp = datetime.now().strftime("%Y%m%d-%H%M%S-%f")[:-3]
    accumulated = []
    
    # 推理参数
    cached_audio_length_sum = sample_rate * cached_audio_duration
    audio_end_idx = cached_audio_duration * tgt_fps
    audio_start_idx = audio_end_idx - frame_num
    
    # 生成视频帧
    audio_dq = deque([0.0] * cached_audio_length_sum, maxlen=cached_audio_length_sum)
    for human_speech_array in human_speech_array_slices:
        audio_dq.extend(human_speech_array.tolist())
        audio_array = np.array(audio_dq)
        audio_embedding = get_audio_embedding(pipeline, audio_array, audio_start_idx, audio_end_idx)
        torch.cuda.synchronize()
        video = run_pipeline(pipeline, audio_embedding)
        video = video[motion_frames_num:]
        torch.cuda.synchronize()
        accumulated.append(torch.from_numpy(video.cpu().numpy()))
    
    # 保存初始视频
    temp_dir = tempfile.mkdtemp()
    silent_audio_path = os.path.join(temp_dir, "silent.wav")
    _save_chunk_audio_to_wav(create_silent_audio(duration=0.5), silent_audio_path, sample_rate)
    
    initial_video_path = os.path.join(stream_dir, f"initial_{timestamp}.mp4")
    save_video_with_audio(accumulated, initial_video_path, silent_audio_path, fps=tgt_fps)
    current_video_state = initial_video_path
    
    logger.info(f"数字人初始化完成，初始视频: {initial_video_path}")
    
    return initial_video_path


# ========== Gradio UI构建 ==========
with gr.Blocks(title="SoulX-FlashHead 数字人聊天", theme=gr.themes.Soft()) as app:
    gr.Markdown("# 🎭 SoulX-FlashHead 数字人实时聊天")
    gr.Markdown("发送文字消息，数字人会通过视频回复你！")
    
    with gr.Row():
        with gr.Column(scale=1):
            with gr.Group():
                gr.Markdown("### ⚙️ 设置")
                
                # API Key输入
                api_key_input = gr.Textbox(
                    label="火山引擎 ARK API Key",
                    type="password",
                    placeholder="请输入您的 ARK API Key",
                    value=os.getenv("ARK_API_KEY", ""),
                )
                
                # 数字人照片输入
                cond_image_input = gr.Image(
                    label="数字人照片",
                    type="filepath",
                    value="examples/girl.png",
                    height=300,
                )
            
            # 高级设置（默认折叠）
            with gr.Accordion("🔧 高级设置", open=False):
                ckpt_dir_input = gr.Textbox(
                    label="FlashHead Checkpoint Directory",
                    value="models/SoulX-FlashHead-1_3B",
                )
                wav2vec_dir_input = gr.Textbox(
                    label="Wav2Vec Directory",
                    value="models/wav2vec2-base-960h",
                )
                model_type_input = gr.Dropdown(
                    label="Model Type",
                    choices=["pro", "lite"],
                    value="lite",
                )
                use_face_crop_input = gr.Checkbox(label="Use Face Crop", value=False)
                seed_input = gr.Number(label="Random Seed", value=9999, precision=0)
        
        with gr.Column(scale=2):
            with gr.Group():
                gr.Markdown("### 📺 数字人")
                # 视频输出组件
                video_output = gr.Video(
                    label="数字人视频",
                    height=512,
                    format="mp4",
                    streaming=True,
                    autoplay=True
                )
            
            with gr.Group():
                gr.Markdown("### 💬 聊天记录")
                # 聊天记录组件
                chatbot = gr.Chatbot(
                    label="聊天对话",
                    height=300,
                )
                
                with gr.Row():
                    # 消息输入框
                    message_input = gr.Textbox(
                        label="输入消息",
                        placeholder="请输入您想说的话...",
                        scale=4,
                    )
                    # 发送按钮
                    send_btn = gr.Button(
                        "发送",
                        variant="primary",
                        scale=1,
                    )
    
    # 状态变量：跟踪当前显示的视频
    current_video_state_var = gr.State(value=None)
    
    # 应用加载时的初始化
    def on_app_load():
        initial_video = initialize_digital_human(
            "examples/girl.png",
            "models/SoulX-FlashHead-1_3B",
            "models/wav2vec2-base-960h",
            "lite",
            9999,
            False
        )
        return initial_video, initial_video
    
    app.load(
        fn=on_app_load,
        outputs=[video_output, current_video_state_var],
    )
    
    # 发送按钮点击事件
    send_btn.click(
        fn=chat_with_digital_human,
        inputs=[
            message_input,
            api_key_input,
            cond_image_input,
            ckpt_dir_input,
            wav2vec_dir_input,
            model_type_input,
            seed_input,
            use_face_crop_input,
            chatbot,
            current_video_state_var,
        ],
        outputs=[chatbot, video_output],
    ).then(
        lambda: "",  # 清空输入框
        outputs=message_input,
    )
    
    # 消息输入框回车事件
    message_input.submit(
        fn=chat_with_digital_human,
        inputs=[
            message_input,
            api_key_input,
            cond_image_input,
            ckpt_dir_input,
            wav2vec_dir_input,
            model_type_input,
            seed_input,
            use_face_crop_input,
            chatbot,
            current_video_state_var,
        ],
        outputs=[chatbot, video_output],
    ).then(
        lambda: "",  # 清空输入框
        outputs=message_input,
    )

if __name__ == "__main__":
    app.launch(share=False)
