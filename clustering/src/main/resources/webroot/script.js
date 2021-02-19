const INTERRUPT_SIGNAL = "__HYPERFOIL_INTERRUPT_SIGNAL__"
const PAGER_MAGIC = "__HYPERFOIL_PAGER_MAGIC__\n"
const EDIT_MAGIC = "__HYPERFOIL_EDIT_MAGIC__\n"
const EDIT_COMPLETE_MAGIC = "__HYPERFOIL_EDIT_COMPLETE_MAGIC__\n"
const DOWNLOAD_MAGIC = "__HYPERFOIL_DOWNLOAD_MAGIC__"
const DIRECT_DOWNLOAD_MAGIC = "__HYPERFOIL_DIRECT_DOWNLOAD_MAGIC__\n";
const DIRECT_DOWNLOAD_END = "__HYPERFOIL_DIRECT_DOWNLOAD_END__\n";

const ansiUp = new AnsiUp();
const resultWindow = document.getElementById("result");
var logo = document.getElementById("logo");
const command = document.getElementById("command");
command.remove();
const upload = document.getElementById("upload");
upload.remove();
const pager = document.getElementById("pager");
const editor = document.getElementById("editor");
const tokenFrame = document.getElementById("token-frame");
tokenFrame.onload = () => {
   authToken = tokenFrame.contentDocument.body.innerText
}
document.onkeydown = event => defaultKeyDown(event)

const socket = new WebSocket("ws://" + window.location.host);
resultWindow.appendChild(command)

socket.onmessage = (event) => {
   addResultToWindow(event.data);
};
socket.onclose = () => {
   command.remove();
   resultWindow.innerHTML += '\n<span style="color: red">CLI connection has been closed. Please reload the page.</span>'
}

command.addEventListener("keydown", (event) => {
   if (logo) {
      logo.classList.add("logo-exit")
      setTimeout(() => {
         if (logo) {
            logo.remove()
            logo = undefined
         }
      }, 2000)
   }
   if (event.key === "Enter") {
      event.preventDefault();
      sendCommand(command.value + '\n');
      command.value = "";
   } else if (event.key === "Tab") {
      event.preventDefault();
      sendCommand(command.value + '\t');
      command.value = "";
   } else if (event.key === "Backspace" && command.value === "") {
      command.remove();
      resultWindow.innerHTML = resultWindow.innerHTML.slice(0, -1);
      resultWindow.appendChild(command)
      command.focus();
      sendCommand('\b');
   } else if (event.key === "ArrowUp") {
      sendCommand('\033[A');
   } else if (event.key === "ArrowDown") {
      sendCommand('\033[B');
   } else if (event.key === "Escape" || (event.key == 'c' && event.ctrlKey)) {
      event.preventDefault();
      command.remove();
      resultWindow.innerHTML += '<span class="ctrl-c">' + command.value + "</span>"
      command.value = ""
      resultWindow.appendChild(command)
      sendCommand(INTERRUPT_SIGNAL)
   }
});

function sendCommand(command) {
   socket.send(command);
}

var authToken;
var authSent = false;
var blocked = false;
var blockedContent = "";
var paging = false;
var editing = false;
var editedBenchmarkForm = undefined;
var editedVersion = ""
var editData = "";
var editCompleting = false;
var downloading = false;
var downloadContent = ""

function addResultToWindow(commandResult) {
   if (!authSent && authToken) {
      sendCommand("__HYPERFOIL_AUTH_TOKEN__" + authToken);
      authSent = true;
   }
   command.remove();
   while (true) {
      if (commandResult.startsWith('\u001b[160D')) {
         // arrow up, ignore
         commandResult = commandResult.slice(6);
      } else if (commandResult.startsWith('\u001b[2K') || commandResult.startsWith('\u001b[K')) {
         commandResult = commandResult.slice(commandResult.indexOf('K') + 1);
         const lastLine = resultWindow.innerHTML.lastIndexOf('\n')
         resultWindow.innerHTML = resultWindow.innerHTML.slice(0, lastLine + 1)
      } else if (commandResult.startsWith('\u001b[1A')) {
          commandResult = commandResult.slice(4);
          if (resultWindow.innerHTML.endsWith('\n')) {
              resultWindow.innerHTML = resultWindow.innerHTML.slice(0, -1)
          }
      } else {
          break
      }
   }
   if (blocked) {
      blockedContent += commandResult;
   } else if (paging) {
      document.getElementById('pager-content').innerHTML += commandResult;
   } else if (editing) {
      window.editor.setValue(window.editor.getValue() + commandResult)
   } else if (editCompleting) {
      editData += commandResult
      checkEditData()
   } else if (downloading) {
      let endIndex = commandResult.indexOf(DIRECT_DOWNLOAD_END)
      if (endIndex >= 0) {
         downloadContent += commandResult.slice(0, endIndex)
         let lineEnd = downloadContent.indexOf('\n')
         let downloadFilename = downloadContent.slice(0, lineEnd)
         download(window.URL.createObjectURL(new Blob([downloadContent.slice(lineEnd + 1)])), downloadFilename)
         downloadContent = ""
         downloading = false
         addResultToWindow(commandResult.slice(endIndex + DIRECT_DOWNLOAD_END.length))
      } else {
          downloadContent += commandResult
      }
   } else if (commandResult.startsWith("__HYPERFOIL_UPLOAD_MAGIC__")) {
      resultWindow.appendChild(upload)
      upload.focus();
      blocked = true;
   } else if (commandResult.startsWith(PAGER_MAGIC)) {
      commandResult = commandResult.slice(PAGER_MAGIC.length);
      paging = true;
      document.getElementById('pager-content').innerHTML = commandResult;
      pager.style.visibility = 'visible'
      pager.focus()
      document.onkeydown = event => {
         event = event || window.event
         if (paging && (event.key === 'Escape' || event.key === 'q')) stopPaging();
      }
   } else if (commandResult.startsWith(EDIT_MAGIC)) {
      commandResult = commandResult.slice(EDIT_MAGIC.length);
      command.remove()
      editing = true;
      editor.style.visibility = 'visible'
      window.editor.setValue(commandResult)
      window.editor.focus()
   } else if (commandResult.startsWith(EDIT_COMPLETE_MAGIC)) {
      editData = commandResult.slice(EDIT_COMPLETE_MAGIC.length)
      editCompleting = true
      checkEditData()
   } else if (commandResult.startsWith(DOWNLOAD_MAGIC)) {
      let parts = commandResult.split(' ')
      download(window.location + parts[1], parts[2])
      resultWindow.appendChild(command)
      command.focus();
   } else if (commandResult.startsWith(DIRECT_DOWNLOAD_MAGIC)) {
      downloading = true;
      downloadContent = commandResult.slice(DIRECT_DOWNLOAD_MAGIC.length)
   } else {
      resultWindow.innerHTML += ansiUp.ansi_to_html(commandResult);
      resultWindow.appendChild(command)
      command.focus();
   }
}

function defaultKeyDown(event) {
   if (!event.ctrlKey && !event.altKey) {
      command.focus();
   }
}

function download(url, filename) {
   const a = document.createElement('a');
   a.href = url;
   a.download = filename;
   a.click();
}

function showUploadExtrasAndSubmit() {
   document.getElementById('upload-benchmark-label').style.display = 'none';
   document.getElementById('upload-files-label').style.display = 'inline-block';
   document.getElementById('upload-submit').style.display = 'inline-block';
}

function uploadBenchmark() {
   var form = new FormData();
   form.append('benchmark', document.getElementById('upload-benchmark').files[0])
   Array.from(document.getElementById('upload-files').files).forEach((file, i) => form.append('extra' + i, file));

   document.getElementById('upload-benchmark-label').style.display = 'inline-block';
   document.getElementById('upload-files-label').style.display = 'none';
   document.getElementById('upload-submit').style.display = 'none';
   upload.remove();

   uploadBenchmarkForm(form, {})
}

function uploadBenchmarkForm(form, headers) {
   resultWindow.innerHTML += "Uploading... "
   return fetch(window.location + "/benchmark", {
      method: 'POST',
      headers: Object.assign(headers, { Authorization: 'Bearer ' + authToken }),
      body: form,
   }).then(res => {
      if (res.ok) {
          resultWindow.innerHTML += " done.\n"
      } else {
          return res.text().then(error => {
              resultWindow.innerHTML += '\n<span style="color: red">' + error + '</span>\n'
          })
      }
   }, error => {
      resultWindow.innerHTML += error
   }).finally(() => {
      blocked = false;
      addResultToWindow(blockedContent)
      blockedContent = ""
   })
}

function stopPaging() {
   document.getElementById('pager-content').innerHtml = ""
   pager.style.visibility = 'hidden'
   paging = false
   document.onkeydown = event => defaultKeyDown(event)
   sendCommand(INTERRUPT_SIGNAL)
}

function saveEdits() {
   editing = false;
   let editedBenchmark = window.editor.getValue()
   editedBenchmarkForm = new FormData()
   editedBenchmarkForm.append('benchmark', new Blob([editedBenchmark]), "benchmark.hf.yaml")
   sendCommand('__HYPERFOIL_EDITS_BEGIN__\n')
   sendCommand(editedBenchmark)
   sendCommand('__HYPERFOIL_EDITS_END__\n')
   window.editor.setValue("");
   editor.style.visibility = 'hidden'
}

function checkEditData() {
   let endOfFiles = editData.indexOf('__HYPERFOIL_EDIT_END_OF_FILES__\n')
   if (endOfFiles < 0) {
      return
   }
   let lines = editData.slice(0, endOfFiles).split('\n')
   editCompleting = false
   editData = "";

   let benchmark = lines[0]
   editedVersion = lines[1]
   var numFiles = 0
   let uploadEntries = document.createElement('div')
   uploadEntries.id = "upload-entries"
   resultWindow.appendChild(uploadEntries)
   for (var i = 2; i < lines.length; ++i) {
      if (lines[i] === "") continue;
      ++numFiles
      uploadEntries.innerHTML += `<label class="hfbutton" style="margin: 2px 0 2px 0">
          <input class="hidden-upload" type="file" onchange="addUploadFile('${lines[i]}', this)">
          ${lines[i]}: <span id="filename">(not uploading)</span>
      </label>\n`
   }
   if (numFiles > 0) {
      uploadEntries.innerHTML += '<input type="button" class="hfbutton" value="Upload" onclick="uploadEditedBenchmark()" />'
   } else {
      uploadEditedBenchmark()
   }
}

function addUploadFile(name, fileInput) {
   editedBenchmarkForm.delete(name)
   editedBenchmarkForm.append(name, fileInput.files[0], name)
   let siblings = fileInput.parentNode.childNodes
   for (var i = 0; i < siblings.length; ++i) {
      if (siblings[i].id === "filename") {
          siblings[i].innerHTML = fileInput.files[0].name
          break
      }
   }
}

function uploadEditedBenchmark() {
   uploadBenchmarkForm(editedBenchmarkForm, editedVersion === "" ? {} : { "if-match" : editedVersion }).finally(() => {
      editedBenchmarkForm = undefined
      editedVersion = ""
      document.getElementById("upload-entries").remove()
      sendCommand(INTERRUPT_SIGNAL)
   })
}

function cancelEdits() {
   editing = false;
   window.editor.setValue("");
   editor.style.visibility = 'hidden'
   resultWindow.appendChild(command)
   command.focus();
   sendCommand(INTERRUPT_SIGNAL)
}
