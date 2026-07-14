FROM registry.cn-hangzhou.aliyuncs.com/library/maven:3.9-eclipse-temurin-8 AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -B

FROM registry.cn-hangzhou.aliyuncs.com/library/eclipse-temurin:8-jre
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8090
ENV PORT=8090
ENTRYPOINT ["java","-jar","app.jar"]
