-- V41: 语音合成切换为阿里云百炼 Qwen-Audio-TTS
-- 旧 MOSS-TTSD 音色统一迁移到 qwen-audio-3.1-tts-flash 支持的龙安欢音色。

ALTER TABLE bot_settings
    ALTER COLUMN default_voice SET DEFAULT 'longanhuan_v3.1';

UPDATE bot_settings
SET default_voice = 'longanhuan_v3.1',
    updated_at = now()
WHERE default_voice LIKE 'fnlp/MOSS-TTSD%'
   OR lower(default_voice) IN (
       'alex', 'benjamin', 'charles', 'david',
       'anna', 'bella', 'claire', 'diana'
   );

COMMENT ON COLUMN bot_settings.default_voice IS
    'Qwen-Audio-TTS 默认音色，例如 longanhuan_v3.1';
