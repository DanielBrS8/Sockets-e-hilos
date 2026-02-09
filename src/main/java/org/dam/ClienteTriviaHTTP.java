package org.dam;

import java.net.URI;
import java.net.http.*;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Scanner;

public class ClienteTriviaHTTP {
    private static final String BASE_URL = "http://localhost:8080";
    private static final HttpClient client = HttpClient.newHttpClient();
    private static String token = null;
    private static String nombreUsuario = null;
    private static volatile boolean ejecutando = true;

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        System.out.println("=== CLIENTE TRIVIA HTTP ===");
        System.out.println("Conectando a " + BASE_URL + "...\n");

        // Registrar jugador
        System.out.print("Ingresa tu nombre de jugador: ");
        nombreUsuario = scanner.nextLine().trim();

        if (nombreUsuario.isEmpty()) {
            nombreUsuario = "Jugador_" + System.currentTimeMillis() % 1000;
        }

        if (!registrar(nombreUsuario)) {
            System.out.println("Error al registrarse. Asegúrate de que el servidor está ejecutándose.");
            scanner.close();
            return;
        }

        System.out.println("\nComandos disponibles:");
        System.out.println("  A, B, C, D  - Responder a la pregunta");
        System.out.println("  /pregunta   - Ver pregunta actual");
        System.out.println("  /estado     - Ver estado del juego");
        System.out.println("  /raidel     - Activar modo especial");
        System.out.println("  /salir      - Salir del juego\n");

        // Thread para recibir mensajes del servidor (long polling)
        Thread receptor = new Thread(() -> {
            while (ejecutando) {
                recibirMensajes();
            }
        });
        receptor.setDaemon(true);
        receptor.start();

        // Loop principal para leer comandos del usuario
        while (ejecutando) {
            String entrada = scanner.nextLine().trim();

            if (entrada.isEmpty()) continue;

            String entradaUpper = entrada.toUpperCase();

            if (entrada.equalsIgnoreCase("/salir")) {
                ejecutando = false;
                System.out.println("Saliendo del juego...");
                break;
            } else if (entrada.equalsIgnoreCase("/pregunta")) {
                mostrarPregunta();
            } else if (entrada.equalsIgnoreCase("/estado")) {
                mostrarEstado();
            } else if (entrada.equalsIgnoreCase("/raidel")) {
                activarRaidel();
            } else if (entradaUpper.length() == 1 && "ABCD".contains(entradaUpper)) {
                enviarRespuesta(entradaUpper);
            } else {
                System.out.println("Comando no reconocido. Usa A/B/C/D para responder o /pregunta, /estado, /salir");
            }
        }

        scanner.close();
    }

    private static boolean registrar(String nombre) {
        try {
            String json = String.format("{\"nombre\": \"%s\"}", nombre);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/register"))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(json))
                .build();

            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                token = extractJsonValue(response.body(), "token");
                String mensaje = extractJsonValue(response.body(), "mensaje");
                System.out.println(mensaje);
                return true;
            } else {
                System.out.println("Error: " + response.body());
                return false;
            }
        } catch (Exception e) {
            System.out.println("Error de conexión: " + e.getMessage());
            return false;
        }
    }

    private static void enviarRespuesta(String respuesta) {
        try {
            String json = String.format("{\"token\": \"%s\", \"respuesta\": \"%s\"}", token, respuesta);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/answer"))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(json))
                .build();

            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
            String mensaje = extractJsonValue(response.body(), "mensaje");
            String error = extractJsonValue(response.body(), "error");

            if (error != null) {
                System.out.println("Error: " + error);
            } else if (mensaje != null) {
                System.out.println(mensaje);
            }
        } catch (Exception e) {
            System.out.println("Error al enviar respuesta: " + e.getMessage());
        }
    }

    private static void mostrarPregunta() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/question"))
                .GET()
                .build();

            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
            String body = response.body();

            if (body.contains("\"activa\": false") || body.contains("\"activa\":false")) {
                String mensaje = extractJsonValue(body, "mensaje");
                System.out.println(mensaje != null ? mensaje : "No hay pregunta activa");
                return;
            }

            String pregunta = extractJsonValue(body, "pregunta");
            String especial = body.contains("\"especial\": true") || body.contains("\"especial\":true")
                ? " [ESPECIAL]" : "";

            System.out.println("\n=== PREGUNTA" + especial + " ===");
            System.out.println(pregunta);

            // Extraer opciones del array
            int start = body.indexOf("\"opciones\":");
            if (start != -1) {
                int arrayStart = body.indexOf("[", start);
                int arrayEnd = body.indexOf("]", arrayStart);
                String opcionesStr = body.substring(arrayStart + 1, arrayEnd);
                String[] opciones = opcionesStr.split(",");
                for (String opcion : opciones) {
                    opcion = opcion.trim().replace("\"", "");
                    System.out.println("  " + opcion);
                }
            }
            System.out.println("==================\n");

        } catch (Exception e) {
            System.out.println("Error al obtener pregunta: " + e.getMessage());
        }
    }

    private static void mostrarEstado() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/status"))
                .GET()
                .build();

            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
            String body = response.body();

            boolean enCurso = body.contains("\"partidaEnCurso\": true") || body.contains("\"partidaEnCurso\":true");

            System.out.println("\n=== ESTADO DEL JUEGO ===");
            System.out.println("Partida en curso: " + (enCurso ? "Sí" : "No"));

            // Extraer ranking
            int rankingStart = body.indexOf("\"ranking\":");
            if (rankingStart != -1) {
                System.out.println("\nRanking:");
                int arrayStart = body.indexOf("[", rankingStart);
                int arrayEnd = body.indexOf("]", arrayStart);
                String rankingStr = body.substring(arrayStart + 1, arrayEnd);

                if (!rankingStr.trim().isEmpty()) {
                    String[] jugadores = rankingStr.split("\\},");
                    int pos = 1;
                    for (String jugador : jugadores) {
                        String nombre = extractJsonValue(jugador + "}", "nombre");
                        String puntos = extractJsonNumber(jugador, "puntos");
                        if (nombre != null) {
                            System.out.println("  " + pos + ". " + nombre + ": " + puntos + " pts");
                            pos++;
                        }
                    }
                } else {
                    System.out.println("  (Sin jugadores aún)");
                }
            }
            System.out.println("========================\n");

        } catch (Exception e) {
            System.out.println("Error al obtener estado: " + e.getMessage());
        }
    }

    private static void activarRaidel() {
        try {
            String json = String.format("{\"token\": \"%s\"}", token);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/raidel"))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(json))
                .build();

            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
            String mensaje = extractJsonValue(response.body(), "mensaje");
            if (mensaje != null) {
                System.out.println(mensaje);
            }
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        }
    }

    private static void recibirMensajes() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/messages?token=" + token))
                .GET()
                .timeout(java.time.Duration.ofSeconds(35))
                .build();

            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
            String body = response.body();

            // Extraer mensajes del array
            int start = body.indexOf("\"mensajes\":");
            if (start != -1) {
                int arrayStart = body.indexOf("[", start);
                int arrayEnd = body.lastIndexOf("]");
                String mensajesStr = body.substring(arrayStart + 1, arrayEnd);

                if (!mensajesStr.trim().isEmpty()) {
                    // Parsear mensajes del array JSON
                    String[] mensajes = mensajesStr.split("\",\"");
                    for (String mensaje : mensajes) {
                        mensaje = mensaje.replace("\"", "")
                                        .replace("\\n", "\n")
                                        .trim();
                        if (!mensaje.isEmpty()) {
                            if (mensaje.equals("FIN_JUEGO")) {
                                System.out.println("\n=== EL JUEGO HA TERMINADO ===");
                                ejecutando = false;
                                return;
                            } else if (mensaje.startsWith("ERROR:")) {
                                System.out.println("[ERROR] " + mensaje.substring(6));
                            } else if (mensaje.startsWith("ESPERA:")) {
                                System.out.println("[ESPERA] " + mensaje.substring(7));
                            } else {
                                System.out.println("[SERVIDOR] " + mensaje);
                            }
                        }
                    }
                }
            }
        } catch (java.net.http.HttpTimeoutException e) {
            // Timeout normal, reintentar
        } catch (Exception e) {
            if (ejecutando) {
                System.out.println("Error de conexión con el servidor: " + e.getMessage());
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static String extractJsonValue(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*\"([^\"]*)\"";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static String extractJsonNumber(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*(-?\\d+)";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : "0";
    }
}
