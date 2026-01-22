package org.dam;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class ServidorTrivia {
    private static final int PUERTO = 8080;
    private static final int MAX_CLIENTES = 10;
    
    // Lista thread-safe de todos los clientes conectados
    private static Set<ManejadorClienteTrivia> clientes = ConcurrentHashMap.newKeySet();
    
    // Control de la partida
    private static volatile boolean partidaEnCurso = false;
    private static int preguntaActual = 0;
    private static String respuestaCorrecta = "";
    
    // Para el ranking de cada pregunta (orden de llegada)
    private static List<RespuestaCliente> respuestasPregunta = Collections.synchronizedList(new ArrayList<>());
    
    // Puntuaciones totales
    private static Map<String, Integer> puntuacionesGlobales = new ConcurrentHashMap<>();
    
    // Preguntas del juego
    private static final String[][] PREGUNTAS = {
        {"¿Cuál es la capital de España?", "A) Barcelona", "B) Madrid", "C) Sevilla", "D) Valencia", "B"},
        {"¿En qué año llegó el hombre a la Luna?", "A) 1965", "B) 1972", "C) 1969", "D) 1975", "C"},
        {"¿Cuál es el planeta más grande del sistema solar?", "A) Saturno", "B) Neptuno", "C) Tierra", "D) Júpiter", "D"},
        {"¿Quién escribió Don Quijote?", "A) Cervantes", "B) Lope de Vega", "C) Quevedo", "D) Calderón", "A"},
        {"¿Cuántos bytes tiene un Kilobyte?", "A) 100", "B) 512", "C) 1024", "D) 2048", "C"}
    };

    // Preguntas especiales de Perú ;) esto va por ti raidel
    private static final String[][] PREGUNTAS_PERU = {
        {"¿Cuál es la capital de Perú?", "A) Lima", "B) Cusco", "C) Arequipa", "D) Trujillo", "A"},
        {"¿Machu Picchu fue construido por qué civilización?", "A) Maya", "B) Azteca", "C) Inca", "D) Olmeca", "C"},
        {"¿Cuál es el plato típico peruano con pescado crudo marinado?", "A) Tacos", "B) Ceviche", "C) Paella", "D) Sushi", "B"},
        {"¿Qué lago navegable más alto del mundo está en Perú?", "A) Titicaca", "B) Victoria", "C) Baikal", "D) Superior", "A"},
        {"¿Cuál es la bebida morada típica de Perú?", "A) Pisco Sour", "B) Inca Kola", "C) Chicha Morada", "D) Mate", "C"}
    };

    // Control de pregunta especial de Perú
    private static volatile boolean preguntaPeruActiva = false;
    private static String respuestaPeruCorrecta = "";
    
    public static void main(String[] args) {
        ExecutorService pool = Executors.newFixedThreadPool(MAX_CLIENTES);
        
        System.out.println("=== SERVIDOR TRIVIA ===");
        System.out.println("Iniciado en puerto " + PUERTO);
        System.out.println("Comandos: START (iniciar partida), NEXT (siguiente pregunta)");
        System.out.println("Esperando jugadores...\n");
        
        // Thread para leer comandos del servidor
        Thread comandos = new Thread(() -> {
            Scanner scanner = new Scanner(System.in);
            while (true) {
                String comando = scanner.nextLine().toUpperCase().trim();
                if (comando.equals("START")) {
                    iniciarPartida();
                } else if (comando.equals("NEXT")) {
                    siguientePregunta();
                } else if (comando.equals("RANKING")) {
                    mostrarRankingGlobal();
                }
            }
        });
        comandos.setDaemon(true);
        comandos.start();
        
        try (ServerSocket serverSocket = new ServerSocket(PUERTO)) {
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Nuevo jugador conectado: " + clientSocket.getInetAddress());
                
                ManejadorClienteTrivia manejador = new ManejadorClienteTrivia(clientSocket);
                clientes.add(manejador);
                pool.execute(manejador);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            pool.shutdown();
        }
    }
    
    // Iniciar partida
    private static void iniciarPartida() {
        if (clientes.isEmpty()) {
            System.out.println("No hay jugadores conectados!");
            return;
        }
        
        partidaEnCurso = true;
        preguntaActual = 0;
        puntuacionesGlobales.clear();
        
        // Inicializar puntuaciones (solo clientes que ya tienen nombre)
        for (ManejadorClienteTrivia cliente : clientes) {
            String nombre = cliente.getNombreUsuario();
            if (nombre != null) {
                puntuacionesGlobales.put(nombre, 0);
            }
        }
        
        broadcast("=== PARTIDA INICIADA ===");
        broadcast("Total de preguntas: " + PREGUNTAS.length);
        enviarPreguntaActual();
    }
    
    // Enviar pregunta actual a todos
    private static void enviarPreguntaActual() {
        if (preguntaActual >= PREGUNTAS.length) {
            finalizarPartida();
            return;
        }
        
        // Limpiar respuestas anteriores
        respuestasPregunta.clear();
        
        // Marcar que los clientes pueden responder
        for (ManejadorClienteTrivia cliente : clientes) {
            cliente.setPuedeResponder(true);
        }
        
        String[] pregunta = PREGUNTAS[preguntaActual];
        respuestaCorrecta = pregunta[5]; // Última posición es la respuesta correcta
        
        broadcast("\n========== PREGUNTA " + (preguntaActual + 1) + "/" + PREGUNTAS.length + " ==========");
        broadcast(pregunta[0]); // Pregunta
        broadcast(pregunta[1]); // Opción A
        broadcast(pregunta[2]); // Opción B
        broadcast(pregunta[3]); // Opción C
        broadcast(pregunta[4]); // Opción D
        broadcast("================================================");
        broadcast("Responde con A, B, C o D:");
        
        System.out.println("\nPregunta " + (preguntaActual + 1) + " enviada. Respuesta correcta: " + respuestaCorrecta);
    }
    
    // Siguiente pregunta
    private static void siguientePregunta() {
        if (!partidaEnCurso) {
            System.out.println("No hay partida en curso. Usa START para iniciar.");
            return;
        }
        
        // Mostrar ranking de la pregunta
        mostrarRankingPregunta();
        
        preguntaActual++;
        enviarPreguntaActual();
    }
    
    // Mostrar ranking de la pregunta actual
    private static void mostrarRankingPregunta() {
        broadcast("\n--- RESULTADOS PREGUNTA " + (preguntaActual + 1) + " ---");
        broadcast("Respuesta correcta: " + respuestaCorrecta);
        
        if (respuestasPregunta.isEmpty()) {
            broadcast("Nadie respondió a tiempo.");
        } else {
            int posicion = 1;
            for (RespuestaCliente r : respuestasPregunta) {
                String estado = r.esCorrecta ? " CORRECTO" : " Incorrecto";
                broadcast(posicion + ". " + r.nombreUsuario + " - " + r.respuesta + " - " + estado);
                posicion++;
            }
        }
        
        // Mostrar puntuaciones actuales
        broadcast("\n--- PUNTUACIONES ACTUALES ---");
        List<Map.Entry<String, Integer>> ranking = new ArrayList<>(puntuacionesGlobales.entrySet());
        ranking.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        
        int pos = 1;
        for (Map.Entry<String, Integer> entry : ranking) {
            broadcast(pos + ". " + entry.getKey() + ": " + entry.getValue() + " pts");
            pos++;
        }
    }
    
    // Finalizar partida
    private static void finalizarPartida() {
        partidaEnCurso = false;
        
        broadcast("\n╔════════════════════════════════════╗");
        broadcast("║      PARTIDA FINALIZADA            ║");
        broadcast("╚════════════════════════════════════╝");
        
        // Ranking final ordenado
        List<Map.Entry<String, Integer>> ranking = new ArrayList<>(puntuacionesGlobales.entrySet());
        ranking.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        
        broadcast("\n RANKING FINAL ");
        int pos = 1;
        for (Map.Entry<String, Integer> entry : ranking) {
            String medalla = "";
            if (pos == 1) medalla = "🥇 ";
            else if (pos == 2) medalla = "🥈 ";
            else if (pos == 3) medalla = "🥉 ";
            
            broadcast(medalla + pos + ". " + entry.getKey() + ": " + entry.getValue() + " puntos");
            pos++;
        }
        
        System.out.println("\nPartida finalizada. Usa START para nueva partida.");
    }
    
    // Mostrar ranking global en consola del servidor
    private static void mostrarRankingGlobal() {
        System.out.println("\n--- RANKING GLOBAL ---");
        for (Map.Entry<String, Integer> entry : puntuacionesGlobales.entrySet()) {
            System.out.println(entry.getKey() + ": " + entry.getValue() + " pts");
        }
    }

    // Easter egg: Pregunta especial de Perú
    public static void activarModoRaidelPeruano(String activador) {
        preguntaPeruActiva = true;
        respuestasPregunta.clear();

        // Habilitar respuestas para todos
        for (ManejadorClienteTrivia cliente : clientes) {
            cliente.setPuedeResponder(true);
        }

        // Secuencia de activación épica
        broadcast("\n");
        broadcast("⚠️  ⚠️  ⚠️  ⚠️  ⚠️  ⚠️  ⚠️  ⚠️  ⚠️  ⚠️  ⚠️  ⚠️");
        broadcast("");
        broadcast("  ██████╗  ██████╗  ██████╗ ████████╗ ██████╗  ██████╗ ██████╗ ██╗      ██████╗ ");
        broadcast("  ██╔══██╗██╔══██╗██╔═══██╗╚══██╔══╝██╔═══██╗██╔════╝██╔═══██╗██║     ██╔═══██╗");
        broadcast("  ██████╔╝██████╔╝██║   ██║   ██║   ██║   ██║██║     ██║   ██║██║     ██║   ██║");
        broadcast("  ██╔═══╝ ██╔══██╗██║   ██║   ██║   ██║   ██║██║     ██║   ██║██║     ██║   ██║");
        broadcast("  ██║     ██║  ██║╚██████╔╝   ██║   ╚██████╔╝╚██████╗╚██████╔╝███████╗╚██████╔╝");
        broadcast("  ╚═╝     ╚═╝  ╚═╝ ╚═════╝    ╚═╝    ╚═════╝  ╚═════╝ ╚═════╝ ╚══════╝ ╚═════╝ ");
        broadcast("");
        broadcast("  ██████╗  █████╗ ██╗██████╗ ███████╗██╗         ██████╗ ███████╗██████╗ ██╗   ██╗ █████╗ ███╗   ██╗ ██████╗ ");
        broadcast("  ██╔══██╗██╔══██╗██║██╔══██╗██╔════╝██║         ██╔══██╗██╔════╝██╔══██╗██║   ██║██╔══██╗████╗  ██║██╔═══██╗");
        broadcast("  ██████╔╝███████║██║██║  ██║█████╗  ██║         ██████╔╝█████╗  ██████╔╝██║   ██║███████║██╔██╗ ██║██║   ██║");
        broadcast("  ██╔══██╗██╔══██║██║██║  ██║██╔══╝  ██║         ██╔═══╝ ██╔══╝  ██╔══██╗██║   ██║██╔══██║██║╚██╗██║██║   ██║");
        broadcast("  ██║  ██║██║  ██║██║██████╔╝███████╗███████╗    ██║     ███████╗██║  ██║╚██████╔╝██║  ██║██║ ╚████║╚██████╔╝");
        broadcast("  ╚═╝  ╚═╝╚═╝  ╚═╝╚═╝╚═════╝ ╚══════╝╚══════╝    ╚═╝     ╚══════╝╚═╝  ╚═╝ ╚═════╝ ╚═╝  ╚═╝╚═╝  ╚═══╝ ╚═════╝ ");
        broadcast("");
        broadcast("⚠️  ⚠️  ⚠️  ⚠️  ⚠️  ⚠️  ⚠️  ⚠️  ⚠️  ⚠️  ⚠️  ⚠️");
        broadcast("");
        broadcast("            🔄 ACTIVANDOSE... 🔄");
        broadcast("");
        broadcast("    ███████████████████████████████████████");
        broadcast("    █                                     █");
        broadcast("    █        🦙      ⛰️      🦙            █");
        broadcast("    █                                     █");
        broadcast("    █    ░░░░░░░░░░░░░░░░░░░░░░░░░░░░    █");
        broadcast("    █    ░░                          ░░    █");
        broadcast("    █    ░░   MACHU PICCHU LOADING   ░░    █");
        broadcast("    █    ░░   ████████████░░░░░░░░   ░░    █");
        broadcast("    █    ░░          69%             ░░    █");
        broadcast("    █    ░░                          ░░    █");
        broadcast("    █    ░░░░░░░░░░░░░░░░░░░░░░░░░░░░    █");
        broadcast("    █                                     █");
        broadcast("    █   🥔 PAPA   🐟 CEVICHE   🍸 PISCO   █");
        broadcast("    █                                     █");
        broadcast("    ███████████████████████████████████████");
        broadcast("");
        broadcast("🇵🇪🇵🇪🇵🇪🇵🇪🇵🇪🇵🇪🇵🇪🇵🇪🇵🇪🇵🇪🇵🇪🇵🇪🇵🇪🇵🇪🇵🇪🇵🇪🇵🇪🇵🇪🇵🇪🇵🇪🇵🇪🇵🇪🇵🇪🇵🇪");
        broadcast("");
        broadcast("  ¡" + activador + " ha invocado el PODER ANCESTRAL DEL PERÚ!");
        broadcast("");
        broadcast("  ⚡ ¡DEMUESTRA QUE ERES UN VERDADERO PERUANO! ⚡");
        broadcast("");
        broadcast("🇵🇪🇵🇪🇵🇪🇵🇪🇵🇪🇵🇪🇵🇪🇵🇪🇵🇪🇵🇪🇵🇪🇵🇪🇵🇪🇵🇪🇵🇪🇵🇪🇵🇪🇵🇪🇵🇪🇵🇪🇵🇪🇵🇪🇵🇪🇵🇪");

        // Seleccionar pregunta aleatoria de Perú
        Random random = new Random();
        String[] preguntaPeru = PREGUNTAS_PERU[random.nextInt(PREGUNTAS_PERU.length)];
        respuestaPeruCorrecta = preguntaPeru[5];

        broadcast("");
        broadcast("╔═══════════════════════════════════════════════════════╗");
        broadcast("║  🦙 PREGUNTA PARA VERDADEROS PERUANOS 🦙              ║");
        broadcast("╚═══════════════════════════════════════════════════════╝");
        broadcast("");
        broadcast("  " + preguntaPeru[0]);
        broadcast("  " + preguntaPeru[1]);
        broadcast("  " + preguntaPeru[2]);
        broadcast("  " + preguntaPeru[3]);
        broadcast("  " + preguntaPeru[4]);
        broadcast("");
        broadcast("══════════════════════════════════════════════════════════");
        broadcast("  🎯 Responde con A, B, C o D:");
        broadcast("══════════════════════════════════════════════════════════");

        System.out.println("\n🇵🇪 PROTOCOLO RAIDEL PERUANO activado por " + activador);
        System.out.println("Pregunta especial enviada. Respuesta: " + respuestaPeruCorrecta);
    }

    // Registrar respuesta de la pregunta especial de Perú
    public static synchronized void registrarRespuestaPeru(String nombreUsuario, String respuesta) {
        respuesta = respuesta.toUpperCase();
        boolean esCorrecta = respuesta.equals(respuestaPeruCorrecta);

        RespuestaCliente rc = new RespuestaCliente(nombreUsuario, respuesta, esCorrecta);
        respuestasPregunta.add(rc);

        if (esCorrecta) {
            int posicion = 0;
            for (RespuestaCliente r : respuestasPregunta) {
                if (r.esCorrecta) posicion++;
            }
            // ¡Puntos dobles por ser pregunta especial!
            int puntos = Math.max(20 - (posicion - 1) * 4, 4);
            puntuacionesGlobales.merge(nombreUsuario, puntos, Integer::sum);

            broadcast("");
            broadcast("🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉");
            broadcast("");
            broadcast("  ███████╗███╗   ██╗██╗  ██╗ ██████╗ ██████╗  █████╗ ██████╗ ██╗   ██╗███████╗███╗   ██╗ █████╗ ██╗");
            broadcast("  ██╔════╝████╗  ██║██║  ██║██╔═══██╗██╔══██╗██╔══██╗██╔══██╗██║   ██║██╔════╝████╗  ██║██╔══██╗██║");
            broadcast("  █████╗  ██╔██╗ ██║███████║██║   ██║██████╔╝███████║██████╔╝██║   ██║█████╗  ██╔██╗ ██║███████║██║");
            broadcast("  ██╔══╝  ██║╚██╗██║██╔══██║██║   ██║██╔══██╗██╔══██║██╔══██╗██║   ██║██╔══╝  ██║╚██╗██║██╔══██║╚═╝");
            broadcast("  ███████╗██║ ╚████║██║  ██║╚██████╔╝██║  ██║██║  ██║██████╔╝╚██████╔╝███████╗██║ ╚████║██║  ██║██╗");
            broadcast("  ╚══════╝╚═╝  ╚═══╝╚═╝  ╚═╝ ╚═════╝ ╚═╝  ╚═╝╚═╝  ╚═╝╚═════╝  ╚═════╝ ╚══════╝╚═╝  ╚═══╝╚═╝  ╚═╝╚═╝");
            broadcast("");
            broadcast("  🦙🦙🦙 ¡" + nombreUsuario + " ES UN VERDADERO PERUANO! 🦙🦙🦙");
            broadcast("");
            broadcast("       ⛰️  El espíritu de Machu Picchu fluye en ti  ⛰️");
            broadcast("       🥔  La papa te da su bendición  🥔");
            broadcast("       🐟  El ceviche te reconoce como uno de los suyos  🐟");
            broadcast("");
            broadcast("  +" + puntos + " PUNTOS DE PERUANIDAD GANADOS");
            broadcast("");
            broadcast("🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉🎉");
            broadcast("");

            System.out.println(nombreUsuario + " respondió " + respuesta + " - ¡VERDADERO PERUANO! (+" + puntos + " pts)");
        } else {
            broadcast("");
            broadcast("  ❌❌❌ " + nombreUsuario + " falló con " + respuesta + " ❌❌❌");
            broadcast("  😢 El cóndor llora... No eres un verdadero peruano... todavía 😢");
            broadcast("");
            System.out.println(nombreUsuario + " respondió " + respuesta + " - Incorrecto");
        }
    }

    // Finalizar pregunta de Perú
    public static void finalizarPreguntaPeru() {
        if (!preguntaPeruActiva) return;

        preguntaPeruActiva = false;
        broadcast("\n🇵🇪 La respuesta correcta era: " + respuestaPeruCorrecta + " 🇵🇪");
        broadcast("¡Gracias por jugar el MODO RAIDEL PERUANO!\n");
    }

    public static boolean isPreguntaPeruActiva() {
        return preguntaPeruActiva;
    }
    
    // Registrar respuesta de un cliente
    public static synchronized void registrarRespuesta(String nombreUsuario, String respuesta) {
        if (!partidaEnCurso) return;
        
        // Convertir a mayúscula
        respuesta = respuesta.toUpperCase();
        
        boolean esCorrecta = respuesta.equals(respuestaCorrecta);
        
        // Añadir al registro de respuestas (el orden determina la posición)
        RespuestaCliente rc = new RespuestaCliente(nombreUsuario, respuesta, esCorrecta);
        respuestasPregunta.add(rc);
        
        // Calcular puntos: más puntos cuanto más rápido
        // El primero en acertar: 10 pts, segundo: 8 pts, tercero: 6 pts, etc.
        if (esCorrecta) {
            int posicion = 0;
            for (RespuestaCliente r : respuestasPregunta) {
                if (r.esCorrecta) posicion++;
            }
            int puntos = Math.max(10 - (posicion - 1) * 2, 2); // Mínimo 2 puntos
            puntuacionesGlobales.merge(nombreUsuario, puntos, Integer::sum);
            
            System.out.println(nombreUsuario + " respondió " + respuesta + " - CORRECTO! (+" + puntos + " pts)");
        } else {
            System.out.println(nombreUsuario + " respondió " + respuesta + " - Incorrecto");
        }
    }
    
    // Broadcast a todos los clientes
    public static void broadcast(String mensaje) {
        for (ManejadorClienteTrivia cliente : clientes) {
            cliente.enviarMensaje(mensaje);
        }
    }
    
    // Remover cliente
    public static void removerCliente(ManejadorClienteTrivia cliente) {
        clientes.remove(cliente);
        if (cliente.getNombreUsuario() != null) {
            puntuacionesGlobales.remove(cliente.getNombreUsuario());
        }
    }
    
    // Clase interna para registrar respuestas
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
