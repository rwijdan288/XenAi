const express = require('express');
const cors = require('cors');
const dotenv = require('dotenv');
const rateLimit = require('express-rate-limit');
const { GoogleGenAI } = require('@google/genai');

dotenv.config();

const app = express();
const PORT = process.env.PORT || 3000;
const API_KEY = process.env.GEMINI_API_KEY || '';

app.use(cors());
app.use(rateLimit({
  windowMs: 15 * 60 * 1000,
  max: 60,
  message: { success: false, error: 'Rate limit exceeded' }
}));

app.use(express.json({ limit: '25mb' }));
app.use(express.urlencoded({ extended: true, limit: '25mb' }));

const ai = new GoogleGenAI({
  apiKey: API_KEY,
  httpOptions: { headers: { 'User-Agent': 'aistudio-build' } }
});

const DEFAULT_INSTRUCTION =
  'Analisis teks dan gambar yang terlihat di layar. Jika terdapat pertanyaan atau pilihan ganda, berikan jawaban terbaik dan jelaskan alasannya dalam Bahasa Indonesia. Jika gambar tidak jelas atau informasi kurang, katakan dengan jujur dan jangan mengarang.';

app.get('/api/health', (req, res) => {
  res.json({
    success: true,
    status: 'online',
    service: 'Xen AI Backend',
    model: 'gemini-3.8-flash',
    apiKeyConfigured: !!API_KEY && API_KEY.length > 5
  });
});

app.post('/api/analyze', async (req, res) => {
  try {
    const { image, instruction } = req.body;
    if (!image) return res.status(400).json({ success: false, error: 'Parameter image wajib disertakan' });
    if (!API_KEY) return res.status(500).json({ success: false, error: 'GEMINI_API_KEY belum dikonfigurasi' });

    let mimeType = 'image/png';
    let base64Data = image;

    if (image.includes('base64,')) {
      const parts = image.split('base64,');
      const header = parts[0];
      base64Data = parts[1];
      const match = header.match(/data:([a-zA-Z0-9]+\/[a-zA-Z0-9-.+]+);/);
      if (match && match[1]) mimeType = match[1];
    }

    base64Data = base64Data.replace(/\s/g, '');

    const response = await ai.models.generateContent({
      model: 'gemini-3.8-flash',
      contents: {
        parts: [
          { inlineData: { mimeType, data: base64Data } },
          { text: instruction || DEFAULT_INSTRUCTION }
        ]
      }
    });

    res.json({
      success: true,
      answer: response.text?.trim() || 'Tidak ada teks jawaban.',
      model: 'gemini-3.8-flash',
      timestamp: new Date().toISOString()
    });
  } catch (err) {
    res.status(500).json({ success: false, error: err.message });
  }
});

app.listen(PORT, '0.0.0.0', () => console.log(`Server running on port ${PORT}`));