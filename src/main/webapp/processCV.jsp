<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">

    <title>CV Processing</title>
    <link rel="preconnect" href="https://fonts.googleapis.com">
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
  <link href="https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@300;400;500;600;700&display=swap" rel="stylesheet">
  
  <script src="https://cdn.tailwindcss.com"></script>
  <link rel="stylesheet" href="<%= request.getContextPath() %>/css/style.css">
    <script src="<%=request.getContextPath()%>/javascript/processUpload.js?1012" defer></script>
    <style>
        body {
            font-family: Arial, sans-serif;
            margin: 20px;
        }
        .tab-container {
            display: flex;
            border-bottom: 2px solid #ccc;
            margin-bottom: 20px;
        }
        .tab-button {
            padding: 10px 20px;
            background: #f1f1f1;
            border: none;
            cursor: pointer;
            font-weight: bold;
            transition: background 0.3s;
        }
        .tab-button.active {
            background: #007bff;
            color: white;
        }
        .tab-content {
            display: none;
        }
        .tab-content.active {
            display: block;
        }
        .section {
            border: 1px solid #ccc;
            padding: 12px;
            margin-bottom: 15px;
            border-radius: 4px;
        }
        .section h3 {
            margin-top: 0;
        }
        .field {
            margin-bottom: 8px;
        }
        label {
            font-weight: bold;
        }
        #logOutput {
            background: #f8f9fa;
            border: 1px solid #dee2e6;
            padding: 10px;
            height: 400px;
            overflow-y: auto;
            font-family: monospace;
            white-space: pre-wrap;
        }
        .upload-container {
            max-width: 600px;
            margin-bottom: 20px;
        }
    </style>
</head>
<body class="bg-gray-50">
    <!-- Header -->
  <jsp:include page="/includes/header.jsp" />

    <!-- Main Content -->
  <main class="max-w-5xl mx-auto px-6 py-16 mt-24 mb-10 bg-white shadow-lg rounded-lg">

    <h1 class="text-3xl md:text-4xl xl:text-5xl font-bold mb-6 leading-tight text-center">CV Processing System</h1>

    <!-- Upload Form (Above Tabs) -->
    <div class="upload-container">
        <div class="section bg-gradient-to-r from-blue-50 to-purple-50 p-6 rounded-lg border border-blue-200 mb-6">
            <h3 class="text-xl font-bold text-blue-700 mb-4">Upload & Process CV</h3>
            <p class="text-gray-700 mb-4">Upload your CV to extract details.
                <span id="accountNote" class="font-semibold text-blue-600">
                </span>
            </p>
            
            <form id="cvForm" enctype="multipart/form-data" class="space-y-4">
                <div class="space-y-2">
                    <label for="cvFile" class="block font-semibold text-gray-700">Select CV File</label>
                    <input type="file" id="cvFile" name="cvFile" accept=".pdf,.doc,.docx" required 
                        class="w-full text-sm text-gray-600 file:mr-3 file:py-2 file:px-4
                                file:rounded-lg file:border-0 file:font-medium
                                file:bg-blue-100 file:text-blue-700 hover:file:bg-blue-200 cursor-pointer" />
                </div>
                
                <div class="flex gap-3">
                    <button type="button" onclick="processCV()" 
                            class="px-6 py-2 rounded-lg font-semibold text-white bg-blue-600 hover:bg-blue-700 transition">
                        Process CV
                    </button>
                    <button type="button" id="saveToProfileBtn" onclick="saveCVToProfile()" 
                            class="px-6 py-2 rounded-lg font-semibold text-white bg-green-600 hover:bg-green-700 transition hidden">
                        Save to My Profile
                    </button>
                </div>
            </form>
        </div>
    </div>

    <script>
    // Check if user is logged in and update UI
    function checkLoginStatus() {
        fetch('<%= request.getContextPath() %>/resume?fnct=getApplicant', {
            method: 'GET',
            credentials: 'include'
        })
        .then(response => response.json())
        .then(data => {
            const noteEl = document.getElementById('accountNote');
            const saveBtn = document.getElementById('saveToProfileBtn');
            
            if (data.applicant && data.applicant.length > 0) {
                // User is logged in
                noteEl.textContent = '✓ Your data will automatically be saved to your profile.';
                saveBtn.classList.add('hidden');
            } else {
                // User not logged in
                noteEl.innerHTML = 'After processing, you can create an account to save this data to your profile.';
                saveBtn.classList.remove('hidden');
            }
        })
        .catch(error => {
            console.log('User not authenticated');
            document.getElementById('saveToProfileBtn').classList.remove('hidden');
        });
    }

    document.addEventListener('DOMContentLoaded', checkLoginStatus);

    let lastResults = null;

    function saveCVToProfile() {
        if (!lastResults) {
            alert('No CV data to save. Please process a CV first.');
            return;
        }
        
        alert('Please create an account to save your CV data.\nRedirecting to sign up...');
        window.location.href = '<%= request.getContextPath() %>/jbseekerlanding.jsp';
    }
    </script>

    <!-- Tabs -->
    <div class="tab-container">
        <button class="tab-button active" onclick="openTab('logs')">Processing Logs</button>
        <button class="tab-button" onclick="openTab('results')">Results</button>
    </div>

    <!-- Logs Tab -->
    <div id="logs" class="tab-content active">
        <div class="section">
            <h3>System Logs</h3>
            <div id="logOutput">Logs will appear here...</div>
        </div>
    </div>

    <!-- Results Tab -->
    <div id="results" class="tab-content">
        <!-- INFO -->
        <div class="section" id="infoSection">
            <h3>Personal Info</h3>
            <div class="field">
                <label>Name:</label>
                <span id="infoName"></span>
            </div>
            <div class="field">
                <label>Email:</label>
                <span id="infoEmail"></span>
            </div>
            <div class="field">
                <label>Phone:</label>
                <span id="infoPhone"></span>
            </div>
        </div>

        <!-- EDUCATION -->
        <div class="section" id="educationSection">
            <h3>Education</h3>
            <div id="educationContent"></div>
        </div>

        <!-- EXPERIENCE -->
        <div class="section" id="experienceSection">
            <h3>Experience</h3>
            <div id="experienceContent"></div>
        </div>

        <!-- SKILLS -->
        <div class="section" id="skillsSection">
            <h3>Skills</h3>
            <div id="skillsContent"></div>
        </div>

        <!-- REFEREES -->
        <div class="section" id="refereesSection">
            <h3>Referees</h3>
            <div id="refereesContent"></div>
        </div>
    </div>
  </main>

    <!-- Footer -->
  <jsp:include page="/includes/footer.jsp" />

    <script>
        function openTab(tabName) {
            // Hide all tabs
            document.querySelectorAll('.tab-content').forEach(tab => {
                tab.classList.remove('active');
            });
            document.querySelectorAll('.tab-button').forEach(btn => {
                btn.classList.remove('active');
            });
            
            // Show selected tab
            document.getElementById(tabName).classList.add('active');
            document.querySelector(`.tab-button[onclick="openTab('${tabName}')"]`).classList.add('active');
        }

        // Function to add log messages
        function addLog(message) {
            const logOutput = document.getElementById('logOutput');
            logOutput.innerHTML += message + '\n';
            logOutput.scrollTop = logOutput.scrollHeight; // Auto-scroll to bottom
        }
    </script>
</body>
</html>