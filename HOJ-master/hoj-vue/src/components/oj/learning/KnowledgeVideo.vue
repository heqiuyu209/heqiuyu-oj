<template>
  <section class="knowledge-video" aria-label="视频与字幕">
    <div class="video-source-heading"><div><strong><i class="el-icon-video-camera" /> {{ source.name || '课程视频' }}</strong><p class="knowledge-muted">{{ duration }} · {{ source.extractionMode === 'ASR_REVIEWED' ? '管理员校订字幕' : '本地语音识别字幕，请留意识别误差' }}</p></div><el-button icon="el-icon-video-play" :loading="opening" :disabled="!allowed || opening" @click="openVideo">{{ mediaUrl ? '重新授权播放' : '打开原视频' }}</el-button></div>
    <p v-if="error" class="knowledge-error" role="alert">{{ error }}</p>
    <video v-if="mediaUrl && allowed" ref="video" :src="mediaUrl" controls preload="metadata" aria-label="课程原视频" @loadedmetadata="locate" @error="playbackError" />
    <div v-if="timeline" class="video-captions">
      <div class="video-caption-heading"><h4>{{ editable ? '按时间段校订字幕' : '讲解字幕' }}</h4><span class="knowledge-muted">{{ timeline.segments.length }} 段 · 点击时间回看原视频</span></div>
      <div v-for="(segment, index) in visibleSegments" :key="offset + index" class="video-caption" :data-video-segment="offset + index">
        <button type="button" class="video-clock" :disabled="!allowed" :aria-label="'回看 '+clock(segment.startMs)+' 的讲解'" @click="seek(segment.startMs)">{{ clock(segment.startMs) }}<span>{{ clock(segment.endMs) }}</span></button>
        <el-input v-if="editable" :value="segment.text" type="textarea" :rows="2" :maxlength="8000" :disabled="!allowed" :aria-label="'校订第 '+(offset+index+1)+' 段字幕'" @input="edit(offset + index, $event)" />
        <p v-else>{{ segment.text }}</p>
      </div>
      <el-pagination v-if="timeline.segments.length > 50" small layout="prev, pager, next, total" :page-size="50" :current-page="page" :total="timeline.segments.length" @current-change="page=$event" />
    </div>
  </section>
</template>
<script>
import api from '@/common/api';
import { responseData, errorMessage } from '@/common/objective';
import { videoTranscript, videoMediaPath, videoClock } from '@/common/knowledgeVideo';
export default {
  name: 'KnowledgeVideo',
  props: { resource: { type: Object, required: true }, content: { type: String, default: '' }, source: { type: Object, default: () => ({}) }, editable: Boolean, allowed: { type: Boolean, default: true }, startSeconds: { type: Number, default: 0 } },
  data: () => ({ mediaUrl: '', opening: false, error: '', generation: 0, page: 1, seekAt: 0, requestedResource: null }),
  computed: {
    identity() { const getters = this.$store.getters; return [getters.userInfo.uid, getters.authEpoch, (getters.userInfo.roleList || []).slice().sort().join(',')].join(':'); },
    resourceKey() { return [this.identity, this.resource.id, this.resource.version, this.resource.draft, this.resource.maintenance].join(':'); },
    timeline() { try { return videoTranscript(this.content, this.editable); } catch (_) { return null; } },
    duration() { return this.timeline ? videoClock(this.timeline.durationMs) : '字幕暂不可用'; },
    offset() { return (this.page - 1) * 50; },
    visibleSegments() { return this.timeline ? this.timeline.segments.slice(this.offset, this.offset + 50) : []; },
  },
  watch: {
    resourceKey() { this.stop(); this.page = 1; this.error = ''; this.locateCaption(); },
    allowed(value) { if (!value) this.stop(); },
    startSeconds() { this.locateCaption(); if (this.mediaUrl) this.seek(this.startSeconds * 1000); },
  },
  mounted() { this.locateCaption(); this.refreshTimer = setInterval(() => { if (this.mediaUrl && this.allowed) this.refreshVideo(); }, 60000); },
  beforeDestroy() { this.stop(); clearInterval(this.refreshTimer); },
  methods: {
    clock: videoClock,
    valid(key, generation) { return this.allowed && key === this.resourceKey && generation === this.generation && !this._isBeingDestroyed; },
    stop() {
      this.generation++; this.opening = false;
      if (this.$refs.video) { this.$refs.video.pause(); this.$refs.video.removeAttribute('src'); this.$refs.video.load(); }
      this.mediaUrl = '';
      if (this.requestedResource) { api.knowledgeVideoClear(this.requestedResource).catch(() => {}); this.requestedResource = null; }
    },
    async grant() {
      const key = this.resourceKey, generation = this.generation, resource = { ...this.resource };
      this.requestedResource = resource;
      const response = await api.knowledgeVideoAccess(resource, () => this.valid(key, generation));
      if (!response || !this.valid(key, generation)) return null;
      const data = responseData(response);
      if (!data || data.url !== videoMediaPath(resource)) throw new Error('视频播放地址无效，请重新载入');
      return data;
    },
    async openVideo() {
      if (!this.allowed || this.opening) return;
      const key = this.resourceKey, generation = this.generation; this.opening = true; this.error = '';
      try { const data = await this.grant(); if (data && this.valid(key, generation)) this.mediaUrl = data.url; }
      catch (error) { if (this.valid(key, generation)) { this.stop(); this.error = errorMessage(error); } }
      finally { if (this.valid(key, generation)) this.opening = false; }
    },
    async refreshVideo() {
      if (this.opening) return;
      const key = this.resourceKey, generation = this.generation; this.opening = true;
      try { await this.grant(); }
      catch (error) { if (this.valid(key, generation)) { this.stop(); this.error = errorMessage(error); } }
      finally { if (this.valid(key, generation)) this.opening = false; }
    },
    locateCaption() {
      if (!this.timeline) return;
      const milliseconds = Number.isFinite(this.startSeconds) ? Math.max(0, Math.min(this.timeline.durationMs - 1, this.startSeconds * 1000)) : 0;
      this.seekAt = milliseconds / 1000;
      const index = this.timeline.segments.findIndex(segment => segment.endMs > milliseconds);
      this.page = index < 0 ? 1 : Math.floor(index / 50) + 1;
    },
    locate() { if (this.$refs.video && Number.isFinite(this.seekAt)) this.$refs.video.currentTime = this.seekAt; },
    async seek(milliseconds) {
      this.seekAt = milliseconds / 1000;
      if (!this.mediaUrl) await this.openVideo();
      if (this.$refs.video && this.$refs.video.readyState >= 1) this.locate();
    },
    playbackError() { if (!this.mediaUrl) return; this.stop(); this.error = '视频暂不可用，可能已撤回或播放授权已过期。请重新打开视频。'; },
    edit(index, text) {
      if (!this.editable || !this.allowed || !this.timeline) return;
      const timeline = this.timeline; timeline.segments[index] = { ...timeline.segments[index], text };
      const body = JSON.stringify(timeline);
      try { videoTranscript(body, true); this.$emit('input', body); videoTranscript(body); this.error = ''; }
      catch (error) { this.error = error.message; }
    },
  },
};
</script>
<style scoped>
.video-source-heading, .video-caption-heading { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; }
.video-source-heading { border-bottom: 1px solid var(--line); padding-bottom: 18px; }
.video-source-heading strong { color: var(--text); overflow-wrap: anywhere; }
.video-source-heading p { margin: 8px 0 0; }
video { display: block; width: 100%; max-height: 520px; margin: 22px 0; border-radius: 10px; background: #000; }
.video-caption-heading { margin: 24px 0 6px; }.video-caption-heading h4 { margin: 0; font-size: 17px; }
.video-caption { display: grid; grid-template-columns: 116px minmax(0, 1fr); gap: 18px; padding: 18px 0; border-bottom: 1px solid var(--line); }
.video-caption p { margin: 0; color: var(--text); white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.85; }
.video-clock { border: 0; background: transparent; color: var(--accent); padding: 3px 0; text-align: left; font-size: 12px; font-variant-numeric: tabular-nums; cursor: pointer; align-self: start; }
.video-clock span { display: block; margin-top: 5px; color: var(--muted); }.video-clock:focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }
@media (max-width: 760px) { .video-caption { grid-template-columns: 1fr; gap: 8px; }.video-clock span { display: inline; margin-left: 12px; } }
</style>
