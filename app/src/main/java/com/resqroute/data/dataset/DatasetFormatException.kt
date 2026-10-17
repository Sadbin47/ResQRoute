package com.resqroute.data.dataset

/** The bundled dataset or its manifest is invalid, corrupt or unsupported. */
class DatasetFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)
