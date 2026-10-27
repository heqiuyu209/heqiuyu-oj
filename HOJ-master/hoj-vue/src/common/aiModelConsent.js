import api from '@/common/api';
import { responseData } from '@/common/objective';

// Only a local settings read. The caller rechecks its page/session before sending material.
export async function readAiModel(current) {
  const settings = responseData(await api.aiSettings());
  if (!current()) return null;
  if (settings.contestBlocked) throw { data: { status: 403, msg: '你正在参赛，比赛结束后恢复 AI 助教' } };
  if (!settings.configured || !/^[a-f0-9]{64}$/.test(settings.fingerprint || '')) throw { data: { status: 400, msg: '请先在 AI 助教中配置模型服务，或刷新模型设置后重试' } };
  return settings.fingerprint;
}
