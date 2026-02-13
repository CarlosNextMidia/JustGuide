package com.milton.justguide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

// Este ViewModel cuidará da lógica da nossa tela inicial.
class StartViewModel : ViewModel() {

    // LiveData é o coração do MVVM.
    // Ele permite que a tela (Activity) observe mudanças sem a necessidade de checar o tempo todo.

    // 1. Evento para pedir as permissões ao usuário
    // Usamos um SingleLiveEvent (simples MutableLiveData neste caso) para garantir que
    // o pedido de permissão só seja acionado uma vez por clique.
    private val _requestPermissionEvent = MutableLiveData<Unit>()
    val requestPermissionEvent: LiveData<Unit> = _requestPermissionEvent

    // 2. Estado do botão (Pode ser útil para desabilitar/habilitar)
    private val _isRecordingAvailable = MutableLiveData<Boolean>(false)
    val isRecordingAvailable: LiveData<Boolean> = _isRecordingAvailable

    // 3. Destino (o texto que o usuário digitou)
    private val _destination = MutableLiveData<String>()
    val destination: LiveData<String> = _destination

    /**
     * Chamado quando o usuário clica no botão "INICIAR GRAVAÇÃO".
     */
    fun onStartRecordingClicked() {
        // Envia um sinal para a Activity para que ela comece o processo de pedir permissão.
        // A Activity é quem realmente pode interagir com o sistema Android para isso.
        _requestPermissionEvent.value = Unit
    }

    /**
     * Chamado pela Activity após o usuário conceder TODAS as permissões necessárias.
     */
    fun onPermissionsGranted() {
        _isRecordingAvailable.value = true
        // Futuramente, aqui chamaremos a função para IR para a tela de Mapa/Câmera
    }

    /**
     * Atualiza o destino digitado pelo usuário.
     */
    fun updateDestination(newDestination: String) {
        _destination.value = newDestination
    }
}