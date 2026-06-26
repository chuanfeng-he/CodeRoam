FROM node:22-alpine

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev
COPY relay ./relay

EXPOSE 9000
CMD ["node", "relay/src/server.js"]
