# --- build stage ---
FROM node:22-alpine AS build
WORKDIR /app
# Install from the lockfile for reproducible builds; this layer caches until deps change.
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# --- runtime stage ---
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
