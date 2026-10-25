package com.march7th.hkrpg.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.march7th.hkrpg.data.log.LogEntry
import com.march7th.hkrpg.data.log.LogFileInfo
import com.march7th.hkrpg.domain.service.CoreSessionLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 日志历史 ViewModel
 */
class LogHistoryViewModel(private val sessionLogger: CoreSessionLogger) : ViewModel() {

    private val _logFiles = MutableStateFlow<List<LogFileInfo>>(emptyList())
    val logFiles: StateFlow<List<LogFileInfo>> = _logFiles.asStateFlow()

    private val _selectedLogEntries = MutableStateFlow<List<LogEntry>?>(null)
    val selectedLogEntries: StateFlow<List<LogEntry>?> = _selectedLogEntries.asStateFlow()

    private val _selectedFileName = MutableStateFlow<String?>(null)
    val selectedFileName: StateFlow<String?> = _selectedFileName.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadLogFiles()
    }

    /**
     * 加载日志文件列表
     */
    fun loadLogFiles() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val files = withContext(Dispatchers.IO) {
                    sessionLogger.getLogFiles()
                }
                _logFiles.value = files
                Timber.d("Loaded ${files.size} log files")
            } catch (e: Exception) {
                Timber.e(e, "Failed to load log files")
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 加载日志文件内容
     */
    fun loadLogContent(logFile: LogFileInfo) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val entries = withContext(Dispatchers.IO) {
                    sessionLogger.readLogFile(logFile.fileName)
                }
                _selectedLogEntries.value = entries
                _selectedFileName.value = logFile.fileName
                Timber.d("Loaded ${entries.size} entries from ${logFile.fileName}")
            } catch (e: Exception) {
                Timber.e(e, "Failed to load log content")
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 清除选中的日志
     */
    fun clearSelectedLog() {
        _selectedLogEntries.value = null
        _selectedFileName.value = null
    }

    /**
     * 删除日志文件
     */
    fun deleteLogFile(logFile: LogFileInfo) {
        viewModelScope.launch {
            try {
                val success = withContext(Dispatchers.IO) {
                    sessionLogger.deleteLogFile(logFile.fileName)
                }
                if (success) {
                    loadLogFiles() // 刷新列表
                    Timber.d("Deleted log file: ${logFile.fileName}")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete log file")
            }
        }
    }

    /**
     * 清理过期日志
     */
    fun cleanupOldLogs(daysToKeep: Int = 30) {
        viewModelScope.launch {
            try {
                val deletedCount = withContext(Dispatchers.IO) {
                    sessionLogger.cleanupOldLogs(daysToKeep)
                }
                if (deletedCount > 0) {
                    loadLogFiles() // 刷新列表
                    Timber.d("Cleaned up $deletedCount old log files")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to cleanup old logs")
            }
        }
    }
}
