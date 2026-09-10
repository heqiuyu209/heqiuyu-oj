FROM node:22-bookworm-slim AS builder

WORKDIR /build

# 先安装依赖，源码变化时可以复用这一层缓存
COPY hoj-vue/package.json hoj-vue/package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY hoj-vue/ ./

# 兼容当前 Vue CLI 4 使用的旧版 Webpack
RUN NODE_OPTIONS=--openssl-legacy-provider npm run build

FROM nginx:stable-alpine

COPY hoj-deploy/nginx.conf /etc/nginx/conf.d/default.conf

COPY --from=builder /build/dist/ /usr/share/nginx/html/

# HOJ 的滚榜页面是独立的静态资源
COPY hoj-scrollBoard/ /usr/share/nginx/scrollBoard/

EXPOSE 80