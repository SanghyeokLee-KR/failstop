FROM python:3.12-slim@sha256:78387bc3881b8273120a12ebe6c1ab22b018ccc2c9adf565ae1ac9b536e184ea
WORKDIR /app
RUN groupadd --gid 10001 mspconsole && useradd --uid 10001 --gid 10001 --create-home mspconsole && mkdir /state && chown mspconsole:mspconsole /state
COPY --chown=mspconsole:mspconsole . /app
USER mspconsole
ENV CONSOLE_STATE=/state/console.sqlite3 PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
EXPOSE 53002
CMD ["python", "server.py", "--bind", "0.0.0.0"]
