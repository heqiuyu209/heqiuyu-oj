FROM node:18-alpine

WORKDIR /app

COPY package.json ./

RUN npm install --production && npm cache clean --force

COPY server.js ./

EXPOSE 9090

CMD ["node", "server.js"]
