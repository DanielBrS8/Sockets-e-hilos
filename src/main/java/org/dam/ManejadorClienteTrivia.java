package org.dam;

import java.io.*;
import java.net.*;

public class ManejadorClienteTrivia implements Runnable {
    private Socket socket;
    private PrintWriter salida;
    private BufferedReader entrada;
    private String nombreUsuario;
    private volatile boolean puedeResponder = false;
    
    public ManejadorClienteTrivia(Socket socket) {
        this.socket = socket;
    }
    
    @Override
    public void run() {
        try {
            entrada = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            salida = new PrintWriter(socket.getOutputStream(), true);
            
            // Solicitar nombre de usuario
            salida.println("=== TRIVIA GAME ===");
            salida.println("Ingresa tu nombre de jugador:");
            
            nombreUsuario = entrada.readLine();
            if (nombreUsuario == null || nombreUsuario.trim().isEmpty()) {
                nombreUsuario = "Jugador_" + socket.getPort();
            }
            nombreUsuario = nombreUsuario.trim();
            
            System.out.println(nombreUsuario + " se ha unido desde " + socket.getInetAddress());
            
            salida.println("¡Bienvenido " + nombreUsuario + "!");
            salida.println("Esperando a que el servidor inicie la partida...");
            salida.println("Cuando aparezca una pregunta, responde con A, B, C o D\n");
            
            // Notificar a todos
            ServidorTrivia.broadcast("*** " + nombreUsuario + " se ha unido al juego ***");
            
            // Leer respuestas del cliente
            String mensaje;
            while ((mensaje = entrada.readLine()) != null) {
                mensaje = mensaje.trim();
                
                if (mensaje.isEmpty()) {
                    continue;
                }
                
                // Convertir a mayúscula para validar
                String mensajeUpper = mensaje.toUpperCase();
                
                // Comando para salir
                if (mensajeUpper.equals("/SALIR")) {
                    salida.println("¡Hasta luego!");
                    break;
                }

                // Easter egg: RAIDEL PERUANO
                if (mensajeUpper.contains("RAIDEL PERUANO")) {
                    ServidorTrivia.activarModoRaidelPeruano(nombreUsuario);
                    continue;
                }

                // Validar que sea una respuesta válida (A, B, C o D)
                if (mensajeUpper.length() == 1 && "ABCD".contains(mensajeUpper)) {
                    if (!ServidorTrivia.hayPreguntaActiva()) {
                        salida.println("ESPERA:Espera a la siguiente pregunta");
                    } else if (!puedeResponder) {
                        salida.println("ERROR:Ya has respondido a esta pregunta");
                    } else {
                        puedeResponder = false; // Solo puede responder una vez por pregunta

                        // Verificar si es pregunta especial de Perú o normal
                        if (ServidorTrivia.isPreguntaPeruActiva()) {
                            ServidorTrivia.registrarRespuestaPeru(nombreUsuario, mensajeUpper);
                        } else {
                            ServidorTrivia.registrarRespuesta(nombreUsuario, mensajeUpper);
                        }
                        salida.println("Respuesta registrada: " + mensajeUpper);
                        salida.println("Esperando resultados...");
                    }
                } else {
                    salida.println("ERROR:Respuesta invalida. Usa A, B, C o D");
                }
            }
            
        } catch (IOException e) {
            System.out.println("Error con el jugador " + nombreUsuario + ": " + e.getMessage());
        } finally {
            desconectar();
        }
    }
    
    public void enviarMensaje(String mensaje) {
        if (salida != null) {
            salida.println(mensaje);
        }
    }
    
    public String getNombreUsuario() {
        return nombreUsuario;
    }
    
    public boolean isPuedeResponder() {
        return puedeResponder;
    }

    public void setPuedeResponder(boolean puede) {
        this.puedeResponder = puede;
    }
    
    private void desconectar() {
        try {
            ServidorTrivia.removerCliente(this);
            if (nombreUsuario != null) {
                System.out.println(nombreUsuario + " se ha desconectado");
                ServidorTrivia.broadcast("*** " + nombreUsuario + " ha salido del juego ***");
            }
            if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
