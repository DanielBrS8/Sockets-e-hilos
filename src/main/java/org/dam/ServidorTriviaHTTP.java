package org.dam;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class ServidorTriviaHTTP {
    private static final int PUERTO = 8080;

    // Jugadores registrados: token -> nombre
    private static Map<String, String> jugadores = new ConcurrentHashMap<>();

    // Cola de mensajes para cada jugador (para long polling)
    private static Map<String, BlockingQueue<String>> colasMensajes = new ConcurrentHashMap<>();

    // Control de la partida
    private static volatile boolean partidaEnCurso = false;
    private static int preguntaActual = 0;
    private static String respuestaCorrecta = "";

    // Jugadores que ya respondieron la pregunta actual
    private static Set<String> yaRespondieron = ConcurrentHashMap.newKeySet();

    // Respuestas de la pregunta actual
    private static List<RespuestaCliente> respuestasPregunta = Collections.synchronizedList(new ArrayList<>());

    // Puntuaciones globales
    private static Map<String, Integer> puntuacionesGlobales = new ConcurrentHashMap<>();

    // Preguntas del juego
    private static final String[][] PREGUNTAS = {
        {"¿Cuál es la capital de España?", "A) Barcelona", "B) Madrid", "C) Sevilla", "D) Valencia", "B"},
        {"¿En qué año llegó el hombre a la Luna?", "A) 1965", "B) 1972", "C) 1969", "D) 1975", "C"},
        {"¿Cuál es el planeta más grande del sistema solar?", "A) Saturno", "B) Neptuno", "C) Tierra", "D) Júpiter", "D"},
        {"¿Quién escribió Don Quijote?", "A) Cervantes", "B) Lope de Vega", "C) Quevedo", "D) Calderón", "A"},
        {"¿Cuántos bytes tiene un Kilobyte?", "A) 100", "B) 512", "C) 1024", "D) 2048", "C"}
    };

    // Preguntas especiales de Perú
    private static final String[][] PREGUNTAS_PERU = {
        {"¿Cuál es la capital de Perú?", "A) Lima", "B) Cusco", "C) Arequipa", "D) Trujillo", "A"},
        {"¿Machu Picchu fue construido por qué civilización?", "A) Maya", "B) Azteca", "C) Inca", "D) Olmeca", "C"},
        {"¿Cuál es el plato típico peruano con pescado crudo marinado?", "A) Tacos", "B) Ceviche", "C) Paella", "D) Sushi", "B"},
        {"¿Qué lago navegable más alto del mundo está en Perú?", "A) Titicaca", "B) Victoria", "C) Baikal", "D) Superior", "A"},
        {"¿Cuál es la bebida morada típica de Perú?", "A) Pisco Sour", "B) Inca Kola", "C) Chicha Morada", "D) Mate", "C"}
    };

    private static volatile boolean preguntaPeruActiva = false;
    private static String respuestaPeruCorrecta = "";

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PUERTO), 0);

        // Configurar endpoints
        server.createContext("/api/register", new RegisterHandler());
        server.createContext("/api/question", new QuestionHandler());
        server.createContext("/api/answer", new AnswerHandler());
        server.createContext("/api/status", new StatusHandler());
        server.createContext("/api/messages", new MessagesHandler());
        server.createContext("/api/raidel", new RaidelHandler());

        server.setExecutor(Executors.newFixedThreadPool(20));
        server.start();

        System.out.println("=== SERVIDOR TRIVIA HTTP ===");
        System.out.println("Iniciado en http://localhost:" + PUERTO);
        System.out.println("Comandos: START (iniciar partida), NEXT (siguiente pregunta)");
        System.out.println("\nEndpoints disponibles:");
        System.out.println("  POST /api/register   - Registrar jugador {\"nombre\": \"...\"}");
        System.out.println("  GET  /api/question   - Obtener pregunta actual");
        System.out.println("  POST /api/answer     - Enviar respuesta {\"token\": \"...\", \"respuesta\": \"A/B/C/D\"}");
        System.out.println("  GET  /api/status     - Estado del juego y puntuaciones");
        System.out.println("  GET  /api/messages?token=...  - Long polling para mensajes");
        System.out.println("  POST /api/raidel     - Activar modo especial {\"token\": \"...\"}");
        System.out.println("\nEsperando jugadores...\n");

        // Thread para comandos del servidor
        Thread comandos = new Thread(() -> {
            Scanner scanner = new Scanner(System.in);
            while (true) {
                String comando = scanner.nextLine().toUpperCase().trim();
                switch (comando) {
                    case "START" -> iniciarPartida();
                    case "NEXT" -> siguientePregunta();
                    case "RANKING" -> mostrarRankingGlobal();
                }
            }
        });
        comandos.setDaemon(true);
        comandos.start();
    }

    // === HANDLERS HTTP ===

    static class RegisterHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCorsHeaders(exchange);

            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\": \"Método no permitido\"}");
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String nombre = extractJsonValue(body, "nombre");

            if (nombre == null || nombre.trim().isEmpty()) {
                sendResponse(exchange, 400, "{\"error\": \"Nombre requerido\"}");
                return;
            }

            nombre = nombre.trim();
            String token = UUID.randomUUID().toString();

            jugadores.put(token, nombre);
            colasMensajes.put(token, new LinkedBlockingQueue<>());
            puntuacionesGlobales.put(nombre, 0);

            System.out.println(nombre + " se ha registrado (token: " + token.substring(0, 8) + "...)");
            broadcast("*** " + nombre + " se ha unido al juego ***");

            String response = String.format(
                "{\"token\": \"%s\", \"nombre\": \"%s\", \"mensaje\": \"Bienvenido %s! Esperando inicio de partida...\"}",
                token, nombre, nombre
            );
            sendResponse(exchange, 200, response);
        }
    }

    static class QuestionHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCorsHeaders(exchange);

            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\": \"Método no permitido\"}");
                return;
            }

            if (!partidaEnCurso && !preguntaPeruActiva) {
                sendResponse(exchange, 200, "{\"activa\": false, \"mensaje\": \"No hay partida en curso\"}");
                return;
            }

            String[][] preguntas = preguntaPeruActiva ? PREGUNTAS_PERU : PREGUNTAS;
            int idx = preguntaPeruActiva ? new Random().nextInt(PREGUNTAS_PERU.length) : preguntaActual;

            if (!preguntaPeruActiva && preguntaActual >= PREGUNTAS.length) {
                sendResponse(exchange, 200, "{\"activa\": false, \"mensaje\": \"Partida finalizada\"}");
                return;
            }

            String[] pregunta = preguntas[idx];
            String response = String.format(
                "{\"activa\": true, \"numero\": %d, \"total\": %d, \"pregunta\": \"%s\", " +
                "\"opciones\": [\"%s\", \"%s\", \"%s\", \"%s\"], \"especial\": %b}",
                preguntaActual + 1, PREGUNTAS.length,
                escapeJson(pregunta[0]),
                escapeJson(pregunta[1]), escapeJson(pregunta[2]),
                escapeJson(pregunta[3]), escapeJson(pregunta[4]),
                preguntaPeruActiva
            );
            sendResponse(exchange, 200, response);
        }
    }

    static class AnswerHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCorsHeaders(exchange);

            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\": \"Método no permitido\"}");
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String token = extractJsonValue(body, "token");
            String respuesta = extractJsonValue(body, "respuesta");

            if (token == null || !jugadores.containsKey(token)) {
                sendResponse(exchange, 401, "{\"error\": \"Token inválido\"}");
                return;
            }

            if (respuesta == null || respuesta.length() != 1 || !"ABCD".contains(respuesta.toUpperCase())) {
                sendResponse(exchange, 400, "{\"error\": \"ERROR:Respuesta invalida. Usa A, B, C o D\"}");
                return;
            }

            String nombre = jugadores.get(token);
            respuesta = respuesta.toUpperCase();

            if (!partidaEnCurso && !preguntaPeruActiva) {
                sendResponse(exchange, 400, "{\"error\": \"ESPERA:Espera a la siguiente pregunta\"}");
                return;
            }

            if (yaRespondieron.contains(token)) {
                sendResponse(exchange, 400, "{\"error\": \"ERROR:Ya has respondido a esta pregunta\"}");
                return;
            }

            yaRespondieron.add(token);

            if (preguntaPeruActiva) {
                registrarRespuestaPeru(nombre, respuesta);
            } else {
                registrarRespuesta(nombre, respuesta);
            }

            String response = String.format(
                "{\"registrada\": true, \"respuesta\": \"%s\", \"mensaje\": \"Respuesta registrada. Esperando resultados...\"}",
                respuesta
            );
            sendResponse(exchange, 200, response);
        }
    }

    static class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCorsHeaders(exchange);

            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\": \"Método no permitido\"}");
                return;
            }

            StringBuilder ranking = new StringBuilder("[");
            List<Map.Entry<String, Integer>> sorted = new ArrayList<>(puntuacionesGlobales.entrySet());
            sorted.sort((a, b) -> b.getValue().compareTo(a.getValue()));

            for (int i = 0; i < sorted.size(); i++) {
                if (i > 0) ranking.append(",");
                ranking.append(String.format("{\"nombre\": \"%s\", \"puntos\": %d}",
                    escapeJson(sorted.get(i).getKey()), sorted.get(i).getValue()));
            }
            ranking.append("]");

            String response = String.format(
                "{\"partidaEnCurso\": %b, \"preguntaActual\": %d, \"totalPreguntas\": %d, " +
                "\"jugadoresConectados\": %d, \"ranking\": %s}",
                partidaEnCurso, preguntaActual + 1, PREGUNTAS.length,
                jugadores.size(), ranking.toString()
            );
            sendResponse(exchange, 200, response);
        }
    }

    static class MessagesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCorsHeaders(exchange);

            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String query = exchange.getRequestURI().getQuery();
            String token = null;
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] pair = param.split("=");
                    if (pair.length == 2 && "token".equals(pair[0])) {
                        token = pair[1];
                    }
                }
            }

            if (token == null || !jugadores.containsKey(token)) {
                sendResponse(exchange, 401, "{\"error\": \"Token inválido\"}");
                return;
            }

            BlockingQueue<String> cola = colasMensajes.get(token);
            List<String> mensajes = new ArrayList<>();

            try {
                // Long polling: esperar hasta 30 segundos por mensajes
                String mensaje = cola.poll(30, TimeUnit.SECONDS);
                if (mensaje != null) {
                    mensajes.add(mensaje);
                    // Recoger mensajes adicionales disponibles
                    cola.drainTo(mensajes);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            StringBuilder response = new StringBuilder("{\"mensajes\": [");
            for (int i = 0; i < mensajes.size(); i++) {
                if (i > 0) response.append(",");
                response.append("\"").append(escapeJson(mensajes.get(i))).append("\"");
            }
            response.append("]}");

            sendResponse(exchange, 200, response.toString());
        }
    }

    static class RaidelHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            setCorsHeaders(exchange);

            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\": \"Método no permitido\"}");
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String token = extractJsonValue(body, "token");

            if (token == null || !jugadores.containsKey(token)) {
                sendResponse(exchange, 401, "{\"error\": \"Token inválido\"}");
                return;
            }

            String nombre = jugadores.get(token);
            activarModoRaidelPeruano(nombre);

            sendResponse(exchange, 200, "{\"activado\": true, \"mensaje\": \"PROTOCOLO RAIDEL PERUANO ACTIVADO\"}");
        }
    }

    // === LÓGICA DEL JUEGO ===

    private static void iniciarPartida() {
        if (jugadores.isEmpty()) {
            System.out.println("No hay jugadores registrados!");
            return;
        }

        partidaEnCurso = true;
        preguntaActual = 0;

        // Reiniciar puntuaciones
        for (String nombre : jugadores.values()) {
            puntuacionesGlobales.put(nombre, 0);
        }

        broadcast("=== PARTIDA INICIADA ===");
        broadcast("Total de preguntas: " + PREGUNTAS.length);
        enviarPreguntaActual();
    }

    private static void enviarPreguntaActual() {
        if (preguntaActual >= PREGUNTAS.length) {
            finalizarPartida();
            return;
        }

        respuestasPregunta.clear();
        yaRespondieron.clear();

        String[] pregunta = PREGUNTAS[preguntaActual];
        respuestaCorrecta = pregunta[5];

        broadcast("PREGUNTA " + (preguntaActual + 1) + "/" + PREGUNTAS.length);
        broadcast(pregunta[0]);
        broadcast(pregunta[1] + " | " + pregunta[2]);
        broadcast(pregunta[3] + " | " + pregunta[4]);
        broadcast("Responde con A, B, C o D");

        System.out.println("\nPregunta " + (preguntaActual + 1) + " enviada. Respuesta correcta: " + respuestaCorrecta);
    }

    private static void siguientePregunta() {
        if (!partidaEnCurso) {
            System.out.println("No hay partida en curso. Usa START para iniciar.");
            return;
        }

        mostrarRankingPregunta();
        preguntaActual++;
        enviarPreguntaActual();
    }

    private static void mostrarRankingPregunta() {
        broadcast("--- RESULTADOS ---");
        broadcast("Respuesta correcta: " + respuestaCorrecta);

        if (respuestasPregunta.isEmpty()) {
            broadcast("Nadie respondió a tiempo.");
        } else {
            int posicion = 1;
            for (RespuestaCliente r : respuestasPregunta) {
                String estado = r.esCorrecta ? "CORRECTO" : "Incorrecto";
                broadcast(posicion + ". " + r.nombreUsuario + " - " + r.respuesta + " - " + estado);
                posicion++;
            }
        }

        broadcast("--- PUNTUACIONES ---");
        List<Map.Entry<String, Integer>> ranking = new ArrayList<>(puntuacionesGlobales.entrySet());
        ranking.sort((a, b) -> b.getValue().compareTo(a.getValue()));

        int pos = 1;
        for (Map.Entry<String, Integer> entry : ranking) {
            broadcast(pos + ". " + entry.getKey() + ": " + entry.getValue() + " pts");
            pos++;
        }
    }

    private static void finalizarPartida() {
        partidaEnCurso = false;

        broadcast("=== PARTIDA FINALIZADA ===");

        List<Map.Entry<String, Integer>> ranking = new ArrayList<>(puntuacionesGlobales.entrySet());
        ranking.sort((a, b) -> b.getValue().compareTo(a.getValue()));

        broadcast("RANKING FINAL:");
        int pos = 1;
        for (Map.Entry<String, Integer> entry : ranking) {
            String medalla = switch (pos) {
                case 1 -> "[1ro] ";
                case 2 -> "[2do] ";
                case 3 -> "[3ro] ";
                default -> "[" + pos + "] ";
            };
            broadcast(medalla + entry.getKey() + ": " + entry.getValue() + " puntos");
            pos++;
        }

        broadcast("FIN_JUEGO");
        System.out.println("\nPartida finalizada. Usa START para nueva partida.");
    }

    private static void mostrarRankingGlobal() {
        System.out.println("\n--- RANKING GLOBAL ---");
        for (Map.Entry<String, Integer> entry : puntuacionesGlobales.entrySet()) {
            System.out.println(entry.getKey() + ": " + entry.getValue() + " pts");
        }
    }

    private static synchronized void registrarRespuesta(String nombreUsuario, String respuesta) {
        if (!partidaEnCurso) return;

        boolean esCorrecta = respuesta.equals(respuestaCorrecta);
        respuestasPregunta.add(new RespuestaCliente(nombreUsuario, respuesta, esCorrecta));

        if (esCorrecta) {
            int posicion = 0;
            for (RespuestaCliente r : respuestasPregunta) {
                if (r.esCorrecta) posicion++;
            }
            int puntos = Math.max(10 - (posicion - 1) * 2, 2);
            puntuacionesGlobales.merge(nombreUsuario, puntos, Integer::sum);
            System.out.println(nombreUsuario + " respondió " + respuesta + " - CORRECTO! (+" + puntos + " pts)");
        } else {
            System.out.println(nombreUsuario + " respondió " + respuesta + " - Incorrecto");
        }

        // Verificar si todos los jugadores han respondido
        if (yaRespondieron.size() >= jugadores.size()) {
            new Thread(() -> {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                siguientePregunta();
            }).start();
        }
    }

    private static void activarModoRaidelPeruano(String activador) {
        preguntaPeruActiva = true;
        respuestasPregunta.clear();
        yaRespondieron.clear();

        Random random = new Random();
        String[] preguntaPeru = PREGUNTAS_PERU[random.nextInt(PREGUNTAS_PERU.length)];
        respuestaPeruCorrecta = preguntaPeru[5];

        broadcast("PROTOCOLO RAIDEL PERUANO ACTIVADO por " + activador + "!");
        broadcast("PREGUNTA ESPECIAL DE PERU:");
        broadcast(preguntaPeru[0]);
        broadcast(preguntaPeru[1] + " | " + preguntaPeru[2]);
        broadcast(preguntaPeru[3] + " | " + preguntaPeru[4]);
        broadcast("Responde con A, B, C o D (puntos dobles!)");

        System.out.println("\nPROTOCOLO RAIDEL PERUANO activado por " + activador);
        System.out.println("Pregunta especial enviada. Respuesta: " + respuestaPeruCorrecta);
    }

    private static synchronized void registrarRespuestaPeru(String nombreUsuario, String respuesta) {
        boolean esCorrecta = respuesta.equals(respuestaPeruCorrecta);
        respuestasPregunta.add(new RespuestaCliente(nombreUsuario, respuesta, esCorrecta));

        if (esCorrecta) {
            int posicion = 0;
            for (RespuestaCliente r : respuestasPregunta) {
                if (r.esCorrecta) posicion++;
            }
            int puntos = Math.max(20 - (posicion - 1) * 4, 4);
            puntuacionesGlobales.merge(nombreUsuario, puntos, Integer::sum);

            broadcast(nombreUsuario + " ES UN VERDADERO PERUANO! +" + puntos + " pts");
            System.out.println(nombreUsuario + " respondió " + respuesta + " - VERDADERO PERUANO! (+" + puntos + " pts)");
        } else {
            broadcast(nombreUsuario + " falló... No eres un verdadero peruano... todavía");
            System.out.println(nombreUsuario + " respondió " + respuesta + " - Incorrecto");
        }
    }

    // === UTILIDADES ===

    private static void broadcast(String mensaje) {
        for (BlockingQueue<String> cola : colasMensajes.values()) {
            cola.offer(mensaje);
        }
    }

    private static void setCorsHeaders(HttpExchange exchange) {
        Headers headers = exchange.getResponseHeaders();
        headers.add("Access-Control-Allow-Origin", "*");
        headers.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.add("Access-Control-Allow-Headers", "Content-Type");
        headers.add("Content-Type", "application/json; charset=UTF-8");
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String extractJsonValue(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*\"([^\"]*)\"";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }

    static class RespuestaCliente {
        String nombreUsuario;
        String respuesta;
        boolean esCorrecta;

        RespuestaCliente(String nombre, String resp, boolean correcta) {
            this.nombreUsuario = nombre;
            this.respuesta = resp;
            this.esCorrecta = correcta;
        }
    }
}
