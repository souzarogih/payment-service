FROM openjdk:17-jdk-slim

COPY /target/*.jar saga-payment-service-app.jar
EXPOSE 8091
ENTRYPOINT ["java", "-jar", "saga-payment-service-app.jar"]