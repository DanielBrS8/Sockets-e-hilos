package org.dam;

import java.io.*;
import java.net.*;
import java.util.Scanner;

public class ClienteTrivia {
    private static final String HOST = "localhost";
    private static final int PUERTO = 8080;
    
    private Socket socket;
    private PrintWriter salida;
    private BufferedReader entrada;
    private Scanner scanner;
    private volatile boolean conectado = true;
    private volatile boolean nombreRegistrado = false; // Controla si ya ingresó el nombre
    
    public ClienteTrivia() {
        scanner = new Scanner(System.in);
    }
    
    public void iniciar() {
        try {
            socket = new Socket(HOST, PUERTO);
            entrada = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            salida = new PrintWriter(socket.getOutputStream(), true);
            
            System.out.println("Conectado al servidor de Trivia");
            
            // Thread para escuchar mensajes del servidor
            Thread listener = new Thread(new ListenerServidor());
            listener.start();
            
            // Hilo principal para enviar respuestas
            while (conectado) {
                String respuesta = scanner.nextLine();
                
                if (respuesta == null || respuesta.trim().isEmpty()) {
                    continue;
                }
                
                respuesta = respuesta.trim();
                
                // Comando para salir
                if (respuesta.equalsIgnoreCase("/salir")) {
                    salida.println("/salir");
                    conectado = false;
                    break;
                }
                
                // Si aún no ha registrado el nombre, enviar sin validar
                if (!nombreRegistrado) {
                    salida.println(respuesta);
                    nombreRegistrado = true;
                } else {
                    // Ya está en modo juego: validar que sea A, B, C o D
                    String respuestaUpper = respuesta.toUpperCase();
                    if (respuestaUpper.length() == 1 && "ABCD".contains(respuestaUpper)) {
                        salida.println(respuestaUpper);
                    } else if (respuestaUpper.contains("RAIDEL PERUANO")) {
                        // Easter egg: Protocolo Raidel Peruano
                        salida.println(respuesta);
                    } else {
                        System.out.println("⚠ Respuesta inválida. Solo puedes usar A, B, C o D.");
                    }
                }
            }
            
        } catch (IOException e) {
            System.err.println("Error de conexión: " + e.getMessage());
        } finally {
            cerrarConexion();
        }
    }
    
    private void cerrarConexion() {
        try {
            conectado = false;
            if (scanner != null) scanner.close();
            if (salida != null) salida.close();
            if (entrada != null) entrada.close();
            if (socket != null) socket.close();
            System.out.println("Desconectado del servidor");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    // Clase privada para escuchar mensajes del servidor
    private class ListenerServidor implements Runnable {
        @Override
        public void run() {
            try {
                String mensaje;
                while (conectado && (mensaje = entrada.readLine()) != null) {
                    if (mensaje.equals("FIN_JUEGO")) {
                        System.out.println("\n=== EL JUEGO HA TERMINADO ===");
                        conectado = false;
                        break;
                    } else if (mensaje.startsWith("ERROR:")) {
                        System.out.println("[ERROR] " + mensaje.substring(6));
                    } else if (mensaje.startsWith("ESPERA:")) {
                        System.out.println("[ESPERA] " + mensaje.substring(7));
                    } else {
                        System.out.println(mensaje);
                    }
                }
            } catch (IOException e) {
                if (conectado) {
                    System.err.println("Conexión perdida con el servidor");
                }
            }
        }
    }
    
    public static void main(String[] args) {
        System.out.println("╔═══════════════════════════════════╗");
        System.out.println("║       CLIENTE TRIVIA GAME         ║");
        System.out.println("╚═══════════════════════════════════╝");
        System.out.println("Conectando a " + HOST + ":" + PUERTO + "...\n");
        
        ClienteTrivia cliente = new ClienteTrivia();
        cliente.iniciar();
    }
}
