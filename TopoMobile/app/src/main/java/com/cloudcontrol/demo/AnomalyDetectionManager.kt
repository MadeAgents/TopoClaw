package com.cloudcontrol.demo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.exp

/**
 * 异常检测管理器
 * 负责加载模型、执行推理、处理异常检测结果
 */
class AnomalyDetectionManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "AnomalyDetection"
        
        // 检测模型配置
        private const val DETECTION_MODEL_NAME = "0302_xiaobu.tflite"
        private const val DETECTION_INPUT_WIDTH = 480   // width (与云侧一致)
        private const val DETECTION_INPUT_HEIGHT = 960   // height (与云侧一致)
        private const val DETECTION_CONFIDENCE_THRESHOLD = 0.5f
        
        // 分类模型配置
        private const val CLASSIFICATION_MODEL_NAME = "unloaded_fp16.tflite"
        private const val CLASSIFICATION_INPUT_SIZE = 224
        
        // ImageNet标准化参数
        private const val IMAGENET_MEAN_R = 0.485f
        private const val IMAGENET_MEAN_G = 0.456f
        private const val IMAGENET_MEAN_B = 0.406f
        private const val IMAGENET_STD_R = 0.229f
        private const val IMAGENET_STD_G = 0.224f
        private const val IMAGENET_STD_B = 0.225f
        
        @Volatile
        private var instance: AnomalyDetectionManager? = null
        
        /**
         * 获取单例实例
         */
        fun getInstance(context: Context): AnomalyDetectionManager {
            return instance ?: synchronized(this) {
                instance ?: AnomalyDetectionManager(context.applicationContext).also { instance = it }
            }
        }
    }
    
    // 模型解释器
    private var detectionInterpreter: Interpreter? = null
    private var classificationInterpreter: Interpreter? = null
    
    // 模型是否已加载
    private var isDetectionModelLoaded = false
    private var isClassificationModelLoaded = false
    
    /**
     * 检测结果数据类
     */
    data class DetectionResult(
        val hasAnomaly: Boolean,                    // 是否有异常
        val anomalyType: Int? = null,               // 异常类型ID（0-4：权限类、登录类、验证类、支付类、隐私类）
        val anomalyConfidence: Float? = null,       // 异常置信度
        val closeButtonX: Float? = null,           // 关闭按钮中心X坐标（归一化）
        val closeButtonY: Float? = null,           // 关闭按钮中心Y坐标（归一化）
        val closeButtonConfidence: Float? = null    // 关闭按钮置信度
    )
    
    /**
     * 初始化模型（延迟加载）
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        Log.i(TAG, "========== 开始初始化异常检测模型 ==========")
        try {
            // 检查模型文件是否存在
            checkModelFiles()
            
            if (!isDetectionModelLoaded) {
                loadDetectionModel()
            } else {
                Log.i(TAG, "[模型加载] 检测模型已加载，跳过")
            }
            
            if (!isClassificationModelLoaded) {
                loadClassificationModel()
            } else {
                Log.i(TAG, "[模型加载] 分类模型已加载，跳过")
            }
            
            Log.i(TAG, "[模型初始化] ✓✓✓ 异常检测模型初始化完成")
            Log.i(TAG, "[模型状态] 检测模型: ${if (isDetectionModelLoaded) "✓ 已加载" else "✗ 未加载"}")
            Log.i(TAG, "[模型状态] 分类模型: ${if (isClassificationModelLoaded) "✓ 已加载" else "✗ 未加载"}")
            Log.i(TAG, "===========================================")
        } catch (e: Exception) {
            Log.e(TAG, "[模型初始化] ✗✗✗ 模型初始化失败: ${e.message}", e)
            e.printStackTrace()
        }
    }
    
    /**
     * 检查模型文件是否存在
     */
    private fun checkModelFiles() {
        try {
            val detectionExists = try {
                context.assets.open(DETECTION_MODEL_NAME).use { true }
            } catch (e: Exception) {
                false
            }
            
            val classificationExists = try {
                context.assets.open(CLASSIFICATION_MODEL_NAME).use { true }
            } catch (e: Exception) {
                false
            }
            
            Log.i(TAG, "[文件检查] 检测模型文件 ($DETECTION_MODEL_NAME): ${if (detectionExists) "✓ 存在" else "✗ 不存在"}")
            Log.i(TAG, "[文件检查] 分类模型文件 ($CLASSIFICATION_MODEL_NAME): ${if (classificationExists) "✓ 存在" else "✗ 不存在"}")
            
            if (!detectionExists) {
                Log.e(TAG, "[文件检查] ✗✗✗ 检测模型文件不存在！请确保 $DETECTION_MODEL_NAME 在 assets 目录下")
            }
            if (!classificationExists) {
                Log.e(TAG, "[文件检查] ✗✗✗ 分类模型文件不存在！请确保 $CLASSIFICATION_MODEL_NAME 在 assets 目录下")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[文件检查] 检查模型文件时出错: ${e.message}", e)
        }
    }
    
    /**
     * 加载检测模型
     */
    private fun loadDetectionModel() {
        try {
            Log.i(TAG, "[模型加载] 开始加载检测模型: $DETECTION_MODEL_NAME")
            val startTime = System.currentTimeMillis()
            val modelBuffer = loadModelFile(DETECTION_MODEL_NAME)
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                setUseXNNPACK(true) // 使用XNNPACK优化
            }
            detectionInterpreter = Interpreter(modelBuffer, options)
            isDetectionModelLoaded = true
            val loadTime = System.currentTimeMillis() - startTime
            Log.i(TAG, "[模型加载] ✓✓✓ 检测模型加载成功，耗时: ${loadTime}ms, 模型大小: ${modelBuffer.capacity() / 1024 / 1024}MB")
        } catch (e: Exception) {
            Log.e(TAG, "[模型加载] ✗✗✗ 检测模型加载失败: ${e.message}", e)
            isDetectionModelLoaded = false
        }
    }
    
    /**
     * 加载分类模型
     */
    private fun loadClassificationModel() {
        try {
            Log.i(TAG, "[模型加载] 开始加载分类模型: $CLASSIFICATION_MODEL_NAME")
            val startTime = System.currentTimeMillis()
            val modelBuffer = loadModelFile(CLASSIFICATION_MODEL_NAME)
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                setUseXNNPACK(true)
            }
            classificationInterpreter = Interpreter(modelBuffer, options)
            isClassificationModelLoaded = true
            val loadTime = System.currentTimeMillis() - startTime
            Log.i(TAG, "[模型加载] ✓✓✓ 分类模型加载成功，耗时: ${loadTime}ms, 模型大小: ${modelBuffer.capacity() / 1024 / 1024}MB")
        } catch (e: Exception) {
            Log.e(TAG, "[模型加载] ✗✗✗ 分类模型加载失败: ${e.message}", e)
            isClassificationModelLoaded = false
        }
    }
    
    /**
     * 从assets加载模型文件
     * 返回MappedByteBuffer用于TensorFlow Lite Interpreter
     */
    private fun loadModelFile(modelName: String): ByteBuffer {
        try {
            val fileDescriptor = context.assets.openFd(modelName)
            val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.declaredLength
            val buffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
            inputStream.close()
            return buffer
        } catch (e: IOException) {
            Log.e(TAG, "加载模型文件失败: $modelName", e)
            throw e
        }
    }
    
    /**
     * 检测异常
     * @param bitmap 原始截图
     * @return 检测结果
     */
    suspend fun detect(bitmap: Bitmap): DetectionResult = withContext(Dispatchers.Default) {
        if (!isDetectionModelLoaded) {
            Log.w(TAG, "[检测模型] 模型未加载，返回无异常")
            return@withContext DetectionResult(hasAnomaly = false)
        }
        
        try {
            val startTime = System.currentTimeMillis()
            Log.d(TAG, "[检测模型] 开始推理，输入尺寸: ${bitmap.width}x${bitmap.height}")
            // 预处理：resize到480x960并归一化（完全按照云侧逻辑：直接resize，不保持宽高比）
            val preprocessedBitmap = preprocessForDetection(bitmap)
            val inputBuffer = bitmapToFloatBuffer(
                preprocessedBitmap,
                DETECTION_INPUT_WIDTH,
                DETECTION_INPUT_HEIGHT,
                normalize = true
            )
            
            // ========== 诊断：检查模型输出张量信息 ==========
            val numOutputTensors = detectionInterpreter?.outputTensorCount ?: 0
            Log.i(TAG, "========== 模型输出张量诊断 ==========")
            Log.i(TAG, "[诊断] 输出张量数量: $numOutputTensors")
            
            // 检查所有输出张量
            for (i in 0 until numOutputTensors) {
                val tensor = detectionInterpreter?.getOutputTensor(i)
                val shape = tensor?.shape()
                val dataType = tensor?.dataType()
                val tensorName = tensor?.name() ?: "unknown"
                Log.i(TAG, "[诊断] 输出张量[$i]: name=$tensorName, shape=${shape?.contentToString()}, dataType=$dataType")
            }
            Log.i(TAG, "=====================================")
            
            // 获取输出张量信息
            val outputTensor = detectionInterpreter?.getOutputTensor(0)
            val outputShape = outputTensor?.shape()
            val outputType = outputTensor?.dataType()
            
            Log.i(TAG, "[诊断] 使用输出张量[0]: shape=${outputShape?.contentToString()}, dataType=$outputType")
            
            // 根据实际输出形状分配buffer
            val numDetections = outputShape?.get(1) ?: 100 // 默认100
            val outputBuffer = ByteBuffer.allocateDirect(1 * numDetections * 6 * 4) // (1, N, 6) float32
                .order(ByteOrder.nativeOrder())
            outputBuffer.rewind()
            
            detectionInterpreter?.run(inputBuffer, outputBuffer)
            
            // 打印原始输出（推理后立即打印，在解析前）
            outputBuffer.rewind()
            Log.i(TAG, "========== 检测模型原始输出 ==========")
            Log.i(TAG, "[原始输出] 输出形状: (1, $numDetections, 6)")
            Log.i(TAG, "[原始输出] 输出数据类型: $outputType")
            Log.i(TAG, "[原始输出] Buffer字节序: ${outputBuffer.order()}")
            Log.i(TAG, "[原始输出] Buffer总大小: ${outputBuffer.capacity()} 字节")
            Log.i(TAG, "[原始输出] 开始读取原始检测结果...")
            
            // 保存buffer位置，以便多次读取
            val savedPosition = outputBuffer.position()
            
            // 方式1：按文档顺序读取 [x1, y1, x2, y2, confidence, class_id]
            val rawDetections = mutableListOf<String>()
            outputBuffer.position(savedPosition)
            for (i in 0 until numDetections) {
                if (outputBuffer.remaining() < 6 * 4) break
                
                val x1 = outputBuffer.float
                val y1 = outputBuffer.float
                val x2 = outputBuffer.float
                val y2 = outputBuffer.float
                val confidence = outputBuffer.float
                val classId = outputBuffer.float
                
                rawDetections.add("检测$i: [x1=$x1, y1=$y1, x2=$x2, y2=$y2, confidence=$confidence, class_id=$classId]")
            }
            
            // 打印前几个检测结果的原始字节值（用于诊断）
            outputBuffer.position(savedPosition)
            Log.i(TAG, "[原始输出] 前3个检测结果的原始字节值:")
            for (i in 0 until kotlin.math.min(3, numDetections)) {
                if (outputBuffer.remaining() < 6 * 4) break
                val bytes = ByteArray(6 * 4)
                outputBuffer.get(bytes)
                val hexString = bytes.joinToString(" ") { "%02X".format(it) }
                Log.i(TAG, "[原始输出] 检测$i 原始字节: $hexString")
                
                // 手动解析字节验证
                val byteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val x1_manual = byteBuffer.float
                val y1_manual = byteBuffer.float
                val x2_manual = byteBuffer.float
                val y2_manual = byteBuffer.float
                val conf_manual = byteBuffer.float
                val class_manual = byteBuffer.float
                Log.i(TAG, "[原始输出] 检测$i 手动解析: x1=$x1_manual, y1=$y1_manual, x2=$x2_manual, y2=$y2_manual, conf=$conf_manual, class=$class_manual")
                
                // 检查confidence字节位置是否有非零值
                val confBytes = bytes.sliceArray(16..19)
                val confHex = confBytes.joinToString(" ") { "%02X".format(it) }
                Log.i(TAG, "[原始输出] 检测$i confidence字节(位置16-19): $confHex")
                
                // 尝试将class_id位置当作confidence读取
                val classBytes = bytes.sliceArray(20..23)
                val classAsConf = ByteBuffer.wrap(classBytes).order(ByteOrder.LITTLE_ENDIAN).float
                Log.i(TAG, "[原始输出] 检测$i 如果将class_id位置当作confidence读取: $classAsConf")
            }
            
            // 打印所有原始检测结果（限制数量避免日志过长）
            val maxPrint = if (rawDetections.size < 20) rawDetections.size else 20 // 最多打印20个
            Log.i(TAG, "[原始输出] 共检测到 ${rawDetections.size} 个结果，打印前 $maxPrint 个:")
            for (i in 0 until maxPrint) {
                Log.i(TAG, "[原始输出] ${rawDetections[i]}")
            }
            if (rawDetections.size > maxPrint) {
                Log.i(TAG, "[原始输出] ... (还有 ${rawDetections.size - maxPrint} 个结果未显示)")
            }
            
            // 尝试不同的读取顺序（诊断用）
            outputBuffer.position(savedPosition)
            Log.i(TAG, "[原始输出] 尝试不同读取顺序（仅前3个）:")
            for (i in 0 until kotlin.math.min(3, numDetections)) {
                val currentPos = outputBuffer.position()
                if (outputBuffer.remaining() < 6 * 4) break
                
                // 读取6个float值
                val v1 = outputBuffer.float
                val v2 = outputBuffer.float
                val v3 = outputBuffer.float
                val v4 = outputBuffer.float
                val v5 = outputBuffer.float
                val v6 = outputBuffer.float
                
                // 按文档顺序: [x1, y1, x2, y2, confidence, class_id]
                Log.i(TAG, "[原始输出] 检测$i 文档顺序[x1,y1,x2,y2,conf,class_id]: [$v1, $v2, $v3, $v4, $v5, $v6]")
                
                // 关键假设：如果confidence和class_id位置互换
                // 顺序A: [x1, y1, x2, y2, class_id, confidence] - 如果v5是class_id，v6是confidence
                Log.i(TAG, "[原始输出] 检测$i ⚠️假设顺序A[x1,y1,x2,y2,class_id,conf]: [$v1, $v2, $v3, $v4, $v5, $v6]")
                Log.i(TAG, "[原始输出] 检测$i ⚠️  -> 如果v5是class_id($v5), v6是confidence($v6)")
                
                // 检查v6（当前作为class_id）是否在合理范围内作为confidence
                if (v6 >= 0.0f && v6 <= 1.0f && v6 > 0.01f) {
                    Log.i(TAG, "[原始输出] 检测$i ⚠️⚠️⚠️ v6($v6)可能是confidence！范围合理且>0.01")
                }
                
                // 检查v5（当前作为confidence）是否可能是class_id
                if (v5 >= 0.0f && v5 <= 10.0f && v5 == v5.toInt().toFloat()) {
                    Log.i(TAG, "[原始输出] 检测$i ⚠️⚠️⚠️ v5($v5)可能是class_id！看起来像整数类别")
                }
            }
            
            Log.i(TAG, "=====================================")
            
            // 恢复buffer位置
            outputBuffer.position(savedPosition)
            
            // 重新定位buffer到开始位置，准备解析
            outputBuffer.rewind()
            
            // 解析输出
            val detections = parseDetectionOutput(outputBuffer, numDetections, bitmap.width, bitmap.height)
            
            val inferenceTime = System.currentTimeMillis() - startTime
            Log.d(TAG, "[检测模型] 推理完成，耗时: ${inferenceTime}ms, 结果: hasAnomaly=${detections.hasAnomaly}, type=${detections.anomalyType}, confidence=${detections.anomalyConfidence}")
            
            // 回收临时bitmap
            if (preprocessedBitmap != bitmap) {
                preprocessedBitmap.recycle()
            }
            
            detections
        } catch (e: Exception) {
            Log.e(TAG, "异常检测失败: ${e.message}", e)
            DetectionResult(hasAnomaly = false)
        }
    }
    
    /**
     * 检查页面是否加载中
     * @param bitmap 原始截图
     * @return true表示加载中，false表示加载完成
     */
    suspend fun checkLoading(bitmap: Bitmap): Boolean = withContext(Dispatchers.Default) {
        if (!isClassificationModelLoaded) {
            Log.w(TAG, "[分类模型] 模型未加载，返回未加载中")
            return@withContext false
        }
        
        try {
            val startTime = System.currentTimeMillis()
            Log.d(TAG, "[分类模型] 开始推理，输入尺寸: ${bitmap.width}x${bitmap.height}")
            // 预处理：resize到224x224并ImageNet标准化
            val preprocessedBitmap = preprocessForClassification(bitmap)
            val inputBuffer = bitmapToFloatBuffer(
                preprocessedBitmap,
                CLASSIFICATION_INPUT_SIZE,
                CLASSIFICATION_INPUT_SIZE,
                normalize = false, // 分类模型需要ImageNet标准化，不是简单归一化
                imagenetNormalize = true
            )
            
            // 推理
            val outputBuffer = ByteBuffer.allocateDirect(1 * 2 * 4) // (1, 2) float32
                .order(ByteOrder.nativeOrder())
            outputBuffer.rewind()
            
            classificationInterpreter?.run(inputBuffer, outputBuffer)
            
            // 打印原始输出（推理后立即打印，在解析前）
            outputBuffer.rewind()
            val logit0 = outputBuffer.float  // class0: 加载完成
            val logit1 = outputBuffer.float  // class1: 未加载完成
            
            Log.i(TAG, "========== 分类模型原始输出 ==========")
            Log.i(TAG, "[原始输出] 输出形状: (1, 2)")
            Log.i(TAG, "[原始输出] logit_class0 (加载完成) = $logit0")
            Log.i(TAG, "[原始输出] logit_class1 (未加载完成) = $logit1")
            Log.i(TAG, "[原始输出] 原始数组: [$logit0, $logit1]")
            
            // 计算Softmax概率（可选，用于参考）
            val exp0 = kotlin.math.exp(logit0)
            val exp1 = kotlin.math.exp(logit1)
            val sumExp = exp0 + exp1
            val prob0 = exp0 / sumExp
            val prob1 = exp1 / sumExp
            Log.i(TAG, "[原始输出] Softmax概率: P(加载完成)=$prob0, P(未加载完成)=$prob1")
            Log.i(TAG, "=====================================")
            
            // 解析输出：logit_class1 > logit_class0 表示未加载完成
            
            // 回收临时bitmap
            if (preprocessedBitmap != bitmap) {
                preprocessedBitmap.recycle()
            }
            
            val isLoading = logit1 > logit0
            val inferenceTime = System.currentTimeMillis() - startTime
            Log.d(TAG, "[分类模型] 推理完成，耗时: ${inferenceTime}ms, logit0=$logit0, logit1=$logit1, isLoading=$isLoading")
            isLoading
        } catch (e: Exception) {
            Log.e(TAG, "加载状态检测失败: ${e.message}", e)
            false
        }
    }
    
    /**
     * 预处理bitmap用于检测模型（完全按照云侧逻辑：直接resize到480x960，不保持宽高比）
     * 对应云侧：cv2.resize(img_rgb, input_size) 其中 input_size=(480, 960)
     */
    private fun preprocessForDetection(bitmap: Bitmap): Bitmap {
        if (bitmap.width == DETECTION_INPUT_WIDTH && bitmap.height == DETECTION_INPUT_HEIGHT) {
            return bitmap
        }
        
        // 直接resize到目标尺寸，不保持宽高比（与云侧cv2.resize逻辑一致）
        return Bitmap.createScaledBitmap(bitmap, DETECTION_INPUT_WIDTH, DETECTION_INPUT_HEIGHT, true)
    }
    
    /**
     * 预处理bitmap用于分类模型（resize到224x224）
     */
    private fun preprocessForClassification(bitmap: Bitmap): Bitmap {
        if (bitmap.width == CLASSIFICATION_INPUT_SIZE && bitmap.height == CLASSIFICATION_INPUT_SIZE) {
            return bitmap
        }
        return Bitmap.createScaledBitmap(bitmap, CLASSIFICATION_INPUT_SIZE, CLASSIFICATION_INPUT_SIZE, true)
    }
    
    /**
     * 将bitmap转换为FloatBuffer（用于模型输入）
     */
    private fun bitmapToFloatBuffer(
        bitmap: Bitmap,
        width: Int,
        height: Int,
        normalize: Boolean = true,
        imagenetNormalize: Boolean = false
    ): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(1 * height * width * 3 * 4) // float32
            .order(ByteOrder.nativeOrder())
        
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            
            if (imagenetNormalize) {
                // ImageNet标准化
                val rNorm = ((r / 255.0f - IMAGENET_MEAN_R) / IMAGENET_STD_R)
                val gNorm = ((g / 255.0f - IMAGENET_MEAN_G) / IMAGENET_STD_G)
                val bNorm = ((b / 255.0f - IMAGENET_MEAN_B) / IMAGENET_STD_B)
                buffer.putFloat(rNorm)
                buffer.putFloat(gNorm)
                buffer.putFloat(bNorm)
            } else if (normalize) {
                // 简单归一化到[0,1]
                buffer.putFloat(r / 255.0f)
                buffer.putFloat(g / 255.0f)
                buffer.putFloat(b / 255.0f)
            } else {
                // 不归一化
                buffer.putFloat(r.toFloat())
                buffer.putFloat(g.toFloat())
                buffer.putFloat(b.toFloat())
            }
        }
        
        buffer.rewind()
        return buffer
    }
    
    /**
     * 解析检测模型输出
     * @param outputBuffer 模型输出buffer
     * @param numDetections 检测框数量
     * @param originalWidth 原始截图宽度
     * @param originalHeight 原始截图高度
     * @return 检测结果
     */
    private fun parseDetectionOutput(
        outputBuffer: ByteBuffer,
        numDetections: Int,
        originalWidth: Int,
        originalHeight: Int
    ): DetectionResult {
        outputBuffer.rewind()
        
        var hasAnomaly = false
        var anomalyType: Int? = null
        var anomalyConfidence: Float? = null
        var closeButtonX: Float? = null
        var closeButtonY: Float? = null
        var closeButtonConfidence: Float? = null
        
        // 读取检测结果
        var maxAnomalyConfidence = 0f
        var bestAnomalyType: Int? = null
        
        for (i in 0 until numDetections) {
            if (outputBuffer.remaining() < 6 * 4) break // 没有更多数据
            
            val x1 = outputBuffer.float
            val y1 = outputBuffer.float
            val x2 = outputBuffer.float
            val y2 = outputBuffer.float
            val confidence = outputBuffer.float
            val classId = outputBuffer.float.toInt()
            
            // 过滤低置信度结果和无效坐标
            if (confidence < DETECTION_CONFIDENCE_THRESHOLD) continue
            if (x1 < 0 || y1 < 0 || x2 > 1 || y2 > 1 || x1 >= x2 || y1 >= y2) continue
            
            // 新模型类别：0-权限类、1-登录类、2-验证类、3-支付类、4-隐私类（无关闭按钮类）
            if (confidence > maxAnomalyConfidence) {
                maxAnomalyConfidence = confidence
                bestAnomalyType = classId
            }
            hasAnomaly = true
            
            val typeName = when (classId) {
                0 -> "权限类"
                1 -> "登录类"
                2 -> "验证类"
                3 -> "支付类"
                4 -> "隐私类"
                else -> "未知类型"
            }
            Log.i(TAG, "[检测结果] 异常: classId=$classId($typeName), confidence=$confidence, bbox=($x1,$y1,$x2,$y2)")
        }
        
        if (hasAnomaly && bestAnomalyType != null) {
            anomalyType = bestAnomalyType
            anomalyConfidence = maxAnomalyConfidence
        }
        
        return DetectionResult(
            hasAnomaly = hasAnomaly,
            anomalyType = anomalyType,
            anomalyConfidence = anomalyConfidence,
            closeButtonX = closeButtonX,
            closeButtonY = closeButtonY,
            closeButtonConfidence = closeButtonConfidence
        )
    }
    
    /**
     * 将归一化坐标转换为实际像素坐标
     * @param normalizedX 归一化X坐标 [0,1]
     * @param normalizedY 归一化Y坐标 [0,1]
     * @param actualWidth 实际截图宽度
     * @param actualHeight 实际截图高度
     * @return Pair<实际X坐标, 实际Y坐标>
     */
    fun normalizeToActualCoordinates(
        normalizedX: Float,
        normalizedY: Float,
        actualWidth: Int,
        actualHeight: Int
    ): Pair<Int, Int> {
        val actualX = (normalizedX * actualWidth).toInt().coerceIn(0, actualWidth - 1)
        val actualY = (normalizedY * actualHeight).toInt().coerceIn(0, actualHeight - 1)
        return Pair(actualX, actualY)
    }
    
    /**
     * 检查模型加载状态
     * @return Pair<检测模型是否加载, 分类模型是否加载>
     */
    fun getModelStatus(): Pair<Boolean, Boolean> {
        return Pair(isDetectionModelLoaded, isClassificationModelLoaded)
    }
    
    /**
     * 获取模型状态信息（用于日志和调试）
     */
    fun getModelStatusInfo(): String {
        return "检测模型: ${if (isDetectionModelLoaded) "✓ 已加载" else "✗ 未加载"}, " +
               "分类模型: ${if (isClassificationModelLoaded) "✓ 已加载" else "✗ 未加载"}"
    }
    
    /**
     * 释放资源
     */
    fun release() {
        detectionInterpreter?.close()
        classificationInterpreter?.close()
        detectionInterpreter = null
        classificationInterpreter = null
        isDetectionModelLoaded = false
        isClassificationModelLoaded = false
        Log.d(TAG, "异常检测模型已释放")
    }
}

